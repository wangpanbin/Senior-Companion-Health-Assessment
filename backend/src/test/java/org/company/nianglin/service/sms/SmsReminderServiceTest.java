package org.company.nianglin.service.sms;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.company.nianglin.config.SmsProperties;
import org.company.nianglin.constant.SmsScene;
import org.company.nianglin.entity.ElderProfile;
import org.company.nianglin.entity.MedicationTask;
import org.company.nianglin.entity.NotificationPreference;
import org.company.nianglin.entity.SmsSendLog;
import org.company.nianglin.entity.SysUser;
import org.company.nianglin.mapper.ElderProfileMapper;
import org.company.nianglin.mapper.MedicationTaskMapper;
import org.company.nianglin.mapper.NotificationPreferenceMapper;
import org.company.nianglin.mapper.SmsSendLogMapper;
import org.company.nianglin.mapper.SysUserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DuplicateKeyException;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 漏服站外短信编排测试（V4 · P1-3）。
 *
 * <p>覆盖：退订、合规拦截、幂等、静默时段跨零点、指数退避与死信、
 * 无手机号 / 用户已注销。时钟固定为 {@code Asia/Shanghai}，不依赖真实时间。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("漏服站外短信：编排与重试")
class SmsReminderServiceTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final long FAMILY_ID = 101L;
    private static final long ELDER_ID = 401L;
    private static final long TASK_ID = 20002L;
    private static final String PHONE = "13800008888";
    private static final String MASKED_PHONE = "138****8888";
    private static final String TEMPLATE_CODE = "SMS_DEV_MISSED_DOSE";

    @Mock
    private NotificationPreferenceMapper preferenceMapper;
    @Mock
    private SmsSendLogMapper smsSendLogMapper;
    @Mock
    private SysUserMapper sysUserMapper;
    @Mock
    private ElderProfileMapper elderMapper;
    @Mock
    private MedicationTaskMapper medicationTaskMapper;
    @Mock
    private SmsSender smsSender;
    @Mock
    private SmsTemplateRouter templateRouter;

    private SmsProperties properties;
    private SmsReminderService service;
    private MedicationTask task;
    private SysUser familyUser;

    @BeforeEach
    void setUp() {
        properties = org.company.nianglin.support.TestSmsProperties.forMock();
        properties.getRetry().setMaxAttempts(3);
        properties.getRetry().setBaseDelayMs(60_000);
        properties.getRetry().setMaxDelayMs(3_600_000);

        service = new SmsReminderService(preferenceMapper, smsSendLogMapper, sysUserMapper,
                elderMapper, medicationTaskMapper, smsSender, templateRouter, properties,
                fixedClock(LocalDateTime.of(2026, 11, 3, 8, 5)));

        task = new MedicationTask();
        task.setId(TASK_ID);
        task.setElderId(ELDER_ID);
        task.setMedicineName("苯磺酸氨氯地平片");
        task.setPlanTime(LocalDateTime.of(2026, 11, 3, 8, 0));

        ElderProfile elder = new ElderProfile();
        elder.setId(ELDER_ID);
        elder.setName("张三丰");
        when(elderMapper.selectById(ELDER_ID)).thenReturn(elder);

        familyUser = new SysUser();
        familyUser.setId(FAMILY_ID);
        familyUser.setPhone(PHONE);
        lenient().when(sysUserMapper.selectById(FAMILY_ID)).thenReturn(familyUser);

        lenient().when(templateRouter.resolve(SmsScene.MISSED_DOSE)).thenReturn(TEMPLATE_CODE);
        lenient().when(smsSendLogMapper.insert(any(SmsSendLog.class))).thenAnswer(invocation -> {
            SmsSendLog row = invocation.getArgument(0);
            row.setId(1L);
            return 1;
        });
    }

    @Test
    @DisplayName("08:05 非静默 → 立即发送并写 SENT；phone_masked 只存脱敏号")
    void shouldSendImmediatelyOutsideQuietHours() {
        service.enqueueMissedDose(task, List.of(FAMILY_ID));

        ArgumentCaptor<SmsSendLog> inserted = ArgumentCaptor.forClass(SmsSendLog.class);
        verify(smsSendLogMapper).insert(inserted.capture());
        assertEquals(MASKED_PHONE, inserted.getValue().getPhoneMasked());
        assertFalse(inserted.getValue().getPhoneMasked().contains(PHONE), "禁止存完整手机号");

        ArgumentCaptor<Map<String, String>> params = ArgumentCaptor.forClass(Map.class);
        verify(smsSender).send(eq(PHONE), eq(TEMPLATE_CODE), params.capture());
        assertNotNull(params.getValue().get("elderName"));
        assertFalse(params.getValue().get("elderName").contains("张三丰"), "姓名必须先脱敏再进模板");
        assertFalse(params.getValue().containsKey("dosage"), "短信参数禁止剂量");
        assertFalse(params.getValue().containsKey("code"), "漏服提醒不应带验证码参数");

        SmsSendLog persisted = captureSingleUpdate();
        assertEquals("SENT", persisted.getStatus());
        assertEquals(LocalDateTime.of(2026, 11, 3, 8, 5), persisted.getSentAt());
        assertEquals(0, persisted.getAttemptCount(), "成功前 attempt_count 为 0，成功后不额外累加");
    }

    @Test
    @DisplayName("22:30 入队 → PENDING + next_retry_at 次日 08:00，不发送")
    void shouldDeferDuringQuietHoursAcrossMidnight() {
        service = serviceWithClock(LocalDateTime.of(2026, 11, 3, 22, 30));

        service.enqueueMissedDose(task, List.of(FAMILY_ID));

        verify(smsSender, never()).send(anyString(), anyString(), any());
        SmsSendLog persisted = captureSingleUpdate();
        assertEquals("PENDING", persisted.getStatus());
        assertEquals(LocalDateTime.of(2026, 11, 4, 8, 0), persisted.getNextRetryAt());
    }

    @Test
    @DisplayName("家属关闭静默时段 → 22:30 也立即发送")
    void shouldIgnoreQuietHoursWhenFamilyDisabledIt() {
        NotificationPreference preference = new NotificationPreference();
        preference.setUserId(FAMILY_ID);
        preference.setSmsMissedEnabled(1);
        preference.setQuietHoursEnabled(0);
        when(preferenceMapper.selectOne(any())).thenReturn(preference);
        service = serviceWithClock(LocalDateTime.of(2026, 11, 3, 22, 30));

        service.enqueueMissedDose(task, List.of(FAMILY_ID));

        verify(smsSender).send(eq(PHONE), eq(TEMPLATE_CODE), any());
        SmsSendLog persisted = captureSingleUpdate();
        assertEquals("SENT", persisted.getStatus());
    }

    @Test
    @DisplayName("家属关闭站外短信 → 不入队、不发送，站内信不受影响")
    void shouldSkipWhenFamilyUnsubscribed() {
        NotificationPreference preference = new NotificationPreference();
        preference.setUserId(FAMILY_ID);
        preference.setSmsMissedEnabled(0);
        when(preferenceMapper.selectOne(any())).thenReturn(preference);

        service.enqueueMissedDose(task, List.of(FAMILY_ID));

        verify(smsSendLogMapper, never()).insert(any(SmsSendLog.class));
        verify(smsSender, never()).send(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("同一 task 同一天重复入队 → 唯一键冲突，只发 1 次")
    void shouldBeIdempotentWithinSameDay() {
        when(smsSendLogMapper.insert(any(SmsSendLog.class)))
                .thenThrow(new DuplicateKeyException("uk_dedup_key"));

        service.enqueueMissedDose(task, List.of(FAMILY_ID));

        verify(smsSendLogMapper, times(1)).insert(any(SmsSendLog.class));
        verify(smsSender, never()).send(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("文案命中合规词 → DEAD + last_error=compliance:*，且不调用短信通道")
    void shouldDeadLetterWhenComplianceViolated() {
        task.setMedicineName("建议服用的神秘药");

        service.enqueueMissedDose(task, List.of(FAMILY_ID));

        verify(smsSender, never()).send(anyString(), anyString(), any());
        ArgumentCaptor<SmsSendLog> inserted = ArgumentCaptor.forClass(SmsSendLog.class);
        verify(smsSendLogMapper).insert(inserted.capture());
        assertEquals("DEAD", inserted.getValue().getStatus());
        assertNotNull(inserted.getValue().getLastError());
        assertTrue(inserted.getValue().getLastError().startsWith("compliance:"));
    }

    @Test
    @DisplayName("无手机号 / 用户已注销 → FAILED 并留原因，不抛异常")
    void shouldFailPermanentlyWhenUserMissing() {
        when(sysUserMapper.selectById(FAMILY_ID)).thenReturn(null);

        service.enqueueMissedDose(task, List.of(FAMILY_ID));

        verify(smsSender, never()).send(anyString(), anyString(), any());
        SmsSendLog persisted = captureSingleUpdate();
        assertEquals("FAILED", persisted.getStatus());
        assertEquals("user-not-found", persisted.getLastError());
    }

    @Test
    @DisplayName("第 1/2 次失败按指数退避；第 3 次失败 → DEAD 且 last_error 非空")
    void shouldBackOffExponentiallyThenDeadLetter() {
        service = serviceWithClock(LocalDateTime.of(2026, 11, 3, 8, 10));
        doThrow(new IllegalStateException("网关超时 peer down"))
                .when(smsSender).send(anyString(), anyString(), any());

        // 1) 首次发送失败：attempt=1，下次 = now + baseDelay(60s)
        service.enqueueMissedDose(task, List.of(FAMILY_ID));
        SmsSendLog first = captureUpdate(0);
        assertEquals("PENDING", first.getStatus());
        assertEquals(1, first.getAttemptCount());
        assertEquals(LocalDateTime.of(2026, 11, 3, 8, 11), first.getNextRetryAt());

        // 2) 第二次失败：attempt=2，退避翻倍为 120s
        SmsSendLog retry = failedRow(1, LocalDateTime.of(2026, 11, 3, 8, 11));
        stubDueRows(retry);
        service.scanDueRetries();
        SmsSendLog second = captureUpdate(1);
        assertEquals("PENDING", second.getStatus());
        assertEquals(2, second.getAttemptCount());
        assertEquals(LocalDateTime.of(2026, 11, 3, 8, 12), second.getNextRetryAt());

        // 3) 第三次失败：attempt=3 >= maxAttempts → DEAD
        SmsSendLog retry2 = failedRow(2, LocalDateTime.of(2026, 11, 3, 8, 12));
        stubDueRows(retry2);
        service.scanDueRetries();
        SmsSendLog third = captureUpdate(2);
        assertEquals("DEAD", third.getStatus());
        assertEquals(3, third.getAttemptCount());
        assertNotNull(third.getLastError());
        assertTrue(third.getLastError().contains("网关超时"));
    }

    @Test
    @DisplayName("静默时段内重试扫描 → 继续顺延到 08:00，不发送")
    void shouldDeferRetryScanDuringQuietHours() {
        service = serviceWithClock(LocalDateTime.of(2026, 11, 3, 23, 0));
        SmsSendLog row = failedRow(1, LocalDateTime.of(2026, 11, 3, 22, 59));
        stubDueRows(row);

        int handled = service.scanDueRetries();

        assertEquals(1, handled, "顺延的行也要计入本轮处理数");
        verify(smsSender, never()).send(anyString(), anyString(), any());
        assertEquals(LocalDateTime.of(2026, 11, 4, 8, 0), row.getNextRetryAt());
    }

    /* ------------------------------------------------------------------ */

    private SmsReminderService serviceWithClock(LocalDateTime now) {
        return new SmsReminderService(preferenceMapper, smsSendLogMapper, sysUserMapper,
                elderMapper, medicationTaskMapper, smsSender, templateRouter, properties,
                fixedClock(now));
    }

    private static Clock fixedClock(LocalDateTime now) {
        return Clock.fixed(now.atZone(ZONE).toInstant(), ZONE);
    }

    private SmsSendLog failedRow(int attempts, LocalDateTime nextRetryAt) {
        SmsSendLog row = new SmsSendLog();
        row.setId(1L);
        row.setBizType(SmsReminderService.BIZ_TYPE_MEDICATION_MISSED);
        row.setBizId(TASK_ID);
        row.setUserId(FAMILY_ID);
        row.setPhoneMasked(MASKED_PHONE);
        row.setScene(SmsScene.MISSED_DOSE.getKey());
        row.setTemplateCode(TEMPLATE_CODE);
        row.setStatus("PENDING");
        row.setAttemptCount(attempts);
        row.setNextRetryAt(nextRetryAt);
        return row;
    }

    private void stubDueRows(SmsSendLog row) {
        Page<SmsSendLog> page = new Page<>(1, properties.getRetry().getBatchSize(), false);
        page.setRecords(List.of(row));
        when(smsSendLogMapper.selectPage(any(), any())).thenReturn(page);
        when(medicationTaskMapper.selectById(TASK_ID)).thenReturn(task);
    }

    private SmsSendLog captureSingleUpdate() {
        ArgumentCaptor<SmsSendLog> captor = ArgumentCaptor.forClass(SmsSendLog.class);
        verify(smsSendLogMapper).update(captor.capture(), any());
        return captor.getValue();
    }

    private SmsSendLog captureUpdate(int index) {
        ArgumentCaptor<SmsSendLog> captor = ArgumentCaptor.forClass(SmsSendLog.class);
        verify(smsSendLogMapper, times(index + 1)).update(captor.capture(), any());
        return captor.getAllValues().get(index);
    }
}

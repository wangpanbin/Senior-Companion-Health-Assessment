package org.company.nianglin.service.sms;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.company.nianglin.config.SmsProperties;
import org.company.nianglin.constant.MessageType;
import org.company.nianglin.constant.OperationParam;
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
import org.company.nianglin.service.OperationParamService;
import org.company.nianglin.util.ComplianceCheckUtil;
import org.company.nianglin.util.MaskUtil;
import org.company.nianglin.util.MessageTemplateUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 漏服站外短信编排（V4 · P1-3）。
 *
 * <p>本类只负责「短信」这条站外渠道，站内信仍由
 * {@code InternalMessageReminderDispatcher} 独立发送。两条渠道互相隔离：
 * 站内信失败不阻断短信，短信失败也不阻断站内信。</p>
 *
 * <h3>一次漏服提醒的完整流程</h3>
 * <ol>
 *   <li><b>偏好</b>：读 {@code notification_preference}，无记录视为默认开启；
 *       家属关闭站外短信后直接跳过（站内信照发）。</li>
 *   <li><b>文案与合规</b>：复用站内信模板渲染文案并过 {@link ComplianceCheckUtil}，
 *       命中违禁词 → 写 {@code DEAD} 且 {@code last_error=compliance:<词>}，
 *       不静默丢弃。</li>
 *   <li><b>频控</b>：单用户单日上限（{@code nianglin.sms.frequency.missed-dose-daily-cap}，
 *       默认 3）—— 同一位家属当天已入队/已发送的行数达到上限后，后续任务直接跳过，
 *       站内信仍照发。计数只算<b>仍有效任务</b>的回执：任务被删除 / 停用而作废的
 *       {@code VOIDED} 行与孤儿行都不占额度。</li>
 *   <li><b>幂等</b>：{@code dedup_key = medication-missed:<taskId>:<userId>:<yyyy-MM-dd>}，
 *       依赖唯一索引兜底；冲突即视为已入队，继续处理下一位家属。</li>
 *   <li><b>静默时段</b>（默认 22:00–08:00，跨零点）：落在窗口内 →
 *       {@code PENDING} + {@code next_retry_at=次日 08:00}，延后不丢。</li>
 *   <li><b>发送</b>：从 {@code sys_user} 取手机号，调用 {@link SmsSender}；
 *       成功写 {@code SENT}，失败按 {@code baseDelay * 2^(n-1)} 指数退避，
 *       达到 {@code max-attempts} 后进入 {@code DEAD}。</li>
 * </ol>
 *
 * <h3>日志红线</h3>
 * <p>只打印脱敏手机号与 taskId，<b>禁止</b>打印完整手机号、验证码或短信正文。</p>
 *
 * <h3>为什么 dedup_key 含 userId</h3>
 * <p>产品口径是「同一漏服任务、同一位家属、同一天只发一条站外短信」：
 * 每位开启了短信的家属各收一条，而不是「第一位家属抢到、其余静默跳过」。
 * 键由 taskId + userId + 日期组成，{@code uk_dedup_key} 唯一索引结构不变。
 * 跨任务的轰炸由频控兜底（同一用户当天最多 N 条）。</p>
 *
 * @author 银龄伴诊团队
 * @since V4 · P1-3
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SmsReminderService {

    /** 业务类型：漏服提醒 */
    public static final String BIZ_TYPE_MEDICATION_MISSED = "MEDICATION_MISSED";

    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_SENT = "SENT";
    private static final String STATUS_FAILED = "FAILED";
    private static final String STATUS_DEAD = "DEAD";
    /** 任务已删除 / 已停用 → 回执作废：不再发送，也不计入频控额度 */
    private static final String STATUS_VOIDED = "VOIDED";

    private static final int LAST_ERROR_MAX_LENGTH = 500;
    private static final String UNKNOWN_PHONE = "unknown";
    private static final DateTimeFormatter PLAN_TIME_FORMAT = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    private final NotificationPreferenceMapper preferenceMapper;
    private final SmsSendLogMapper smsSendLogMapper;
    private final SysUserMapper sysUserMapper;
    private final ElderProfileMapper elderMapper;
    private final MedicationTaskMapper medicationTaskMapper;
    private final SmsSender smsSender;
    private final SmsTemplateRouter templateRouter;
    private final SmsProperties properties;
    private final Clock clock;

    /**
     * 运营参数服务（V4 · P2-3）；纯单测未注入时为 {@code null}，
     * 回落 {@link SmsProperties} 的 {@code missed-dose-daily-cap}。
     */
    @Autowired(required = false)
    private OperationParamService operationParamService;

    /**
     * 带升级级别的入队入口（V5 · L2 陪诊员短信）。
     *
     * <p><b>级别已透传、行为暂同 L1</b>：dedup 键按级别拆分与「每收件人每级日上限」
     * 在 V5 · A3 收敛（本方法先保证编译与调用面就位，避免两个任务互相卡住）。</p>
     *
     * @param recipients 收件人 ID（家属或当天陪诊员，调用方已过滤空值与去重）
     * @param level      升级级别（1=L1 家属 / 2=L2 陪诊员）
     */
    public void enqueueMissedDose(MedicationTask task, List<Long> recipients, int level) {
        enqueueMissedDose(task, recipients);
    }

    /**
     * 把一条漏服任务的站外短信入队。
     *
     * <p>由 {@code InternalMessageReminderDispatcher#notifyFamily} 在站内信发送之后调用；
     * 本方法<b>不抛出异常</b>，任何单个家属失败都只影响他自己那条短信。</p>
     *
     * @param task      已判定为漏服的服药任务
     * @param familyIds 有效绑定家属 ID（调用方已过滤空值与去重）
     */
    public void enqueueMissedDose(MedicationTask task, List<Long> familyIds) {
        if (task == null || task.getId() == null || familyIds == null || familyIds.isEmpty()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDate today = now.toLocalDate();
        Map<String, Object> params = buildContentParams(task);
        String content = MessageTemplateUtil.content(MessageType.MEDICATION_REMIND, params);
        String complianceHit = ComplianceCheckUtil.firstHit(content);

        for (Long familyId : familyIds) {
            if (familyId == null) {
                continue;
            }
            // 幂等键按「任务 + 家属 + 日期」生成：同一任务下每位家属各发一条
            String dedupKey = buildDedupKey(task.getId(), familyId, today);
            try {
                NotificationPreference preference = loadPreference(familyId);
                if (isSmsDisabled(preference)) {
                    log.info("家属已关闭漏服站外短信，跳过发送 | taskId={} | familyId={}", task.getId(), familyId);
                    continue;
                }
                if (complianceHit != null) {
                    if (insertComplianceDead(task, familyId, dedupKey, complianceHit, now)) {
                        log.error("漏服短信文案命中合规词「{}」，已进入死信 | taskId={} | familyId={}",
                                complianceHit, task.getId(), familyId);
                    }
                    continue;
                }
                if (isDailyCapReached(familyId, now)) {
                    log.info("家属当日漏服短信已达上限，跳过 | taskId={} | familyId={} | cap={}",
                            task.getId(), familyId, effectiveMissedDoseDailyCap());
                    continue;
                }
                SmsSendLog row = buildPendingRow(task, familyId, dedupKey, now);
                if (!insertIgnoreDuplicate(row)) {
                    log.info("漏服短信已入队（幂等键冲突），跳过重复发送 | taskId={} | dedupKey={}",
                            task.getId(), dedupKey);
                    continue;
                }
                if (isQuietHours(now, isQuietEnabled(preference))) {
                    row.setNextRetryAt(nextQuietEnd(now));
                    persist(row);
                    log.info("处于静默时段，漏服短信顺延 | taskId={} | familyId={} | nextRetryAt={}",
                            task.getId(), familyId, row.getNextRetryAt());
                    continue;
                }
                attemptSend(row, params);
            } catch (Exception e) {
                // 单家属失败不能影响其它家属，更不能影响已完成的站内信
                log.warn("漏服短信编排失败（已隔离） | taskId={} | familyId={} | {}",
                        task.getId(), familyId, e.getMessage());
            }
        }
    }

    /**
     * 重试扫描：只处理 {@code status=PENDING && next_retry_at <= now} 的行。
     *
     * <p>由 {@code SmsRetryScheduler} 调用；静默时段内同样顺延到 08:00，不发送。</p>
     *
     * @return 本轮实际处理（含顺延）的行数
     */
    public int scanDueRetries() {
        LocalDateTime now = LocalDateTime.now(clock);
        Page<SmsSendLog> page = new Page<>(1, properties.getRetry().getBatchSize(), false);
        List<SmsSendLog> due = smsSendLogMapper.selectPage(page,
                Wrappers.<SmsSendLog>lambdaQuery()
                        .eq(SmsSendLog::getStatus, STATUS_PENDING)
                        .le(SmsSendLog::getNextRetryAt, now)
                        .orderByAsc(SmsSendLog::getNextRetryAt)).getRecords();
        if (due.isEmpty()) {
            return 0;
        }
        int handled = 0;
        for (SmsSendLog row : due) {
            try {
                if (isQuietHours(now, isQuietEnabled(loadPreference(row.getUserId())))) {
                    row.setNextRetryAt(nextQuietEnd(now));
                    persist(row);
                    handled++;
                    continue;
                }
                MedicationTask task = medicationTaskMapper.selectById(row.getBizId());
                if (task == null) {
                    // 任务已删除 / 停用：回执作废而不是 FAILED，避免继续占用当日额度
                    markVoided(row, "task-not-found");
                    handled++;
                    continue;
                }
                attemptSend(row, buildContentParams(task));
                handled++;
            } catch (Exception e) {
                log.warn("漏服短信重试失败（已隔离） | logId={} | taskId={} | {}",
                        row.getId(), row.getBizId(), e.getMessage());
            }
        }
        return handled;
    }

    /**
     * 作废一批用药任务下尚未发送的短信回执。
     *
     * <p>用药任务被删除 / 停用后调用：{@code PENDING} / {@code FAILED} / {@code DEAD}
     * 一律置 {@code VOIDED}（不再重试、也不再计入当日频控额度），
     * {@code SENT} 保留原状以维持「确实发过」的审计事实。</p>
     *
     * @param taskIds 已删除 / 停用的用药任务 ID
     * @param reason  作废原因，写入 {@code last_error=voided:<reason>}
     * @return 实际作废的行数
     */
    public int voidUnsentForTasks(Collection<Long> taskIds, String reason) {
        if (taskIds == null || taskIds.isEmpty()) {
            return 0;
        }
        int voided = smsSendLogMapper.update(null,
                Wrappers.<SmsSendLog>lambdaUpdate()
                        .in(SmsSendLog::getBizId, taskIds)
                        .eq(SmsSendLog::getScene, SmsScene.MISSED_DOSE.getKey())
                        .in(SmsSendLog::getStatus, List.of(STATUS_PENDING, STATUS_FAILED, STATUS_DEAD))
                        .set(SmsSendLog::getStatus, STATUS_VOIDED)
                        .set(SmsSendLog::getLastError, truncate("voided:" + reason))
                        .set(SmsSendLog::getNextRetryAt, null));
        if (voided > 0) {
            log.info("用药任务已删除 / 停用，作废未发送短信回执 | tasks={} | voided={} | reason={}",
                    taskIds.size(), voided, reason);
        }
        return voided;
    }

    /* ------------------------------------------------------------------ */
    /* 入队与发送                                                          */
    /* ------------------------------------------------------------------ */

    private NotificationPreference loadPreference(Long familyId) {
        return preferenceMapper.selectOne(
                Wrappers.<NotificationPreference>lambdaQuery()
                        .eq(NotificationPreference::getUserId, familyId));
    }

    private static boolean isSmsDisabled(NotificationPreference preference) {
        // 无记录 = 默认开启（迁移不做数据回填，避免给所有家属塞一行默认值）
        return preference != null && preference.getSmsMissedEnabled() != null
                && preference.getSmsMissedEnabled() == 0;
    }

    private static boolean isQuietEnabled(NotificationPreference preference) {
        // 无记录 = 默认开启静默；用户偏好与全局开关是「与」关系
        return preference == null || preference.getQuietHoursEnabled() == null
                || preference.getQuietHoursEnabled() == 1;
    }

    /**
     * 单用户单日频控：当日该场景仍有效任务的回执行数达到上限即拦截。
     *
     * <p>计数口径是「行」而不是「发送成功数」——静默顺延与失败的短信同样占额度，
     * 否则夜间排队 + 次日补偿会把 N 条上限瞬间放大成 2N 条。站内信不受此限。</p>
     *
     * <p><b>只计有效任务</b>（V4 缺陷修复）：任务被删除 / 停用后，其回执即使仍是
     * {@code PENDING} / {@code FAILED} 也不再占额度。两道保险：
     * ① 删除路径会把未发送回执置 {@code VOIDED}；
     * ② 这里再用 {@code biz_id IN (SELECT id FROM medication_task WHERE deleted = 0)}
     * 兜底，历史孤儿行（任务已不存在）不会再阻塞后续发送。</p>
     */
    private boolean isDailyCapReached(Long familyId, LocalDateTime now) {
        long todayCount = smsSendLogMapper.selectCount(
                Wrappers.<SmsSendLog>lambdaQuery()
                        .eq(SmsSendLog::getUserId, familyId)
                        .eq(SmsSendLog::getScene, SmsScene.MISSED_DOSE.getKey())
                        .ne(SmsSendLog::getStatus, STATUS_VOIDED)
                        .ge(SmsSendLog::getCreateTime, now.toLocalDate().atStartOfDay())
                        // 只计仍存在（未逻辑删除）的用药任务回执
                        .inSql(SmsSendLog::getBizId,
                                "SELECT id FROM medication_task WHERE deleted = 0"));
        return todayCount >= effectiveMissedDoseDailyCap();
    }

    /** 运营参数优先；纯单测无 Spring 注入时回落 SmsProperties */
    private int effectiveMissedDoseDailyCap() {
        return operationParamService == null
                ? properties.getFrequency().getMissedDoseDailyCap()
                : operationParamService.getInt(OperationParam.SMS_MISSED_DOSE_DAILY_CAP);
    }

    private SmsSendLog buildPendingRow(MedicationTask task, Long familyId, String dedupKey, LocalDateTime now) {
        SysUser user = sysUserMapper.selectById(familyId);
        String phone = user == null ? null : user.getPhone();
        SmsSendLog row = new SmsSendLog();
        row.setBizType(BIZ_TYPE_MEDICATION_MISSED);
        row.setBizId(task.getId());
        row.setUserId(familyId);
        row.setPhoneMasked(phone == null || phone.isBlank() ? UNKNOWN_PHONE : MaskUtil.phone(phone));
        row.setScene(SmsScene.MISSED_DOSE.getKey());
        row.setTemplateCode(templateRouter.resolve(SmsScene.MISSED_DOSE));
        row.setStatus(STATUS_PENDING);
        row.setAttemptCount(0);
        row.setDedupKey(dedupKey);
        row.setScheduledAt(now);
        return row;
    }

    private boolean insertComplianceDead(MedicationTask task, Long familyId, String dedupKey,
                                         String complianceHit, LocalDateTime now) {
        SmsSendLog row = buildPendingRow(task, familyId, dedupKey, now);
        row.setStatus(STATUS_DEAD);
        row.setLastError(truncate("compliance:" + complianceHit));
        row.setNextRetryAt(null);
        return insertIgnoreDuplicate(row);
    }

    /**
     * 插入 PENDING 行；唯一键冲突返回 {@code false}（视为已入队）。
     *
     * <p>MyBatis-Plus 会把 MySQL 的 1062 包成 {@link DuplicateKeyException}，
     * 但不同驱动层可能抛 {@link DataIntegrityViolationException}，两者都按幂等处理。</p>
     */
    private boolean insertIgnoreDuplicate(SmsSendLog row) {
        try {
            smsSendLogMapper.insert(row);
            return true;
        } catch (DataIntegrityViolationException e) {
            return false;
        }
    }

    private void attemptSend(SmsSendLog row, Map<String, Object> contentParams) {
        SysUser user = sysUserMapper.selectById(row.getUserId());
        if (user == null) {
            markPermanentFailure(row, "user-not-found");
            return;
        }
        String phone = user.getPhone();
        if (phone == null || phone.isBlank()) {
            markPermanentFailure(row, "phone-missing");
            return;
        }
        row.setPhoneMasked(MaskUtil.phone(phone));
        try {
            smsSender.send(phone, row.getTemplateCode(), buildSmsParams(contentParams));
            row.setStatus(STATUS_SENT);
            row.setSentAt(LocalDateTime.now(clock));
            row.setLastError(null);
            row.setNextRetryAt(null);
            persist(row);
            log.info("漏服短信已发送 | taskId={} | phone={}", row.getBizId(), row.getPhoneMasked());
        } catch (Exception e) {
            String reason = sanitizeError(e, phone);
            markRetryableFailure(row, reason);
            log.warn("漏服短信发送失败 | taskId={} | phone={} | attempt={} | status={}",
                    row.getBizId(), row.getPhoneMasked(), row.getAttemptCount(), row.getStatus());
        }
    }

    /**
     * 失败后按指数退避；达到上限进入死信。
     * <p>第 1 次失败退避 {@code base}，第 2 次 {@code 2*base}，第 3 次若仍失败且
     * {@code max-attempts=3} 则直接 {@code DEAD}。</p>
     */
    private void markRetryableFailure(SmsSendLog row, String reason) {
        int attempts = (row.getAttemptCount() == null ? 0 : row.getAttemptCount()) + 1;
        row.setAttemptCount(attempts);
        row.setLastError(truncate(reason));
        if (attempts >= properties.getRetry().getMaxAttempts()) {
            row.setStatus(STATUS_DEAD);
            row.setNextRetryAt(null);
        } else {
            row.setStatus(STATUS_PENDING);
            row.setNextRetryAt(LocalDateTime.now(clock).plusNanos(backoffMs(attempts) * 1_000_000L));
        }
        persist(row);
    }

    private void markPermanentFailure(SmsSendLog row, String reason) {
        row.setAttemptCount((row.getAttemptCount() == null ? 0 : row.getAttemptCount()) + 1);
        row.setStatus(STATUS_FAILED);
        row.setLastError(truncate(reason));
        row.setNextRetryAt(null);
        persist(row);
    }

    /** 任务已不存在 / 已停用 → 回执作废：不再重试，也不占额度 */
    private void markVoided(SmsSendLog row, String reason) {
        row.setStatus(STATUS_VOIDED);
        row.setLastError(truncate("voided:" + reason));
        row.setNextRetryAt(null);
        persist(row);
    }

    /**
     * 显式 set 所有可变列，避免 MyBatis-Plus 默认「null 字段不更新」导致
     * {@code next_retry_at} 在成功 / 死信后仍然残留旧值。
     */
    private void persist(SmsSendLog row) {
        smsSendLogMapper.update(row, Wrappers.<SmsSendLog>lambdaUpdate()
                .eq(SmsSendLog::getId, row.getId())
                .set(SmsSendLog::getPhoneMasked, row.getPhoneMasked())
                .set(SmsSendLog::getStatus, row.getStatus())
                .set(SmsSendLog::getAttemptCount, row.getAttemptCount())
                .set(SmsSendLog::getLastError, row.getLastError())
                .set(SmsSendLog::getSentAt, row.getSentAt())
                .set(SmsSendLog::getNextRetryAt, row.getNextRetryAt()));
    }

    /* ------------------------------------------------------------------ */
    /* 时间与文案                                                          */
    /* ------------------------------------------------------------------ */

    private boolean isQuietHours(LocalDateTime now, boolean userQuietEnabled) {
        if (!userQuietEnabled || !properties.getQuietHours().isEnabled()) {
            return false;
        }
        LocalTime start = properties.getQuietHours().getStartTime();
        LocalTime end = properties.getQuietHours().getEndTime();
        LocalTime current = now.toLocalTime();
        if (start.isAfter(end)) {
            // 跨零点：22:00–08:00 判定为 current >= 22:00 || current < 08:00
            return !current.isBefore(start) || current.isBefore(end);
        }
        return !current.isBefore(start) && current.isBefore(end);
    }

    private LocalDateTime nextQuietEnd(LocalDateTime now) {
        LocalTime end = properties.getQuietHours().getEndTime();
        LocalDate date = now.toLocalTime().isBefore(end) ? now.toLocalDate() : now.toLocalDate().plusDays(1);
        return LocalDateTime.of(date, end.withSecond(0).withNano(0));
    }

    private long backoffMs(int attempts) {
        long base = properties.getRetry().getBaseDelayMs();
        long max = properties.getRetry().getMaxDelayMs();
        long delay = base;
        for (int i = 1; i < attempts; i++) {
            if (delay >= max / 2) {
                return max;
            }
            delay *= 2;
        }
        return Math.min(delay, max);
    }

    private Map<String, Object> buildContentParams(MedicationTask task) {
        ElderProfile elder = elderMapper.selectById(task.getElderId());
        Map<String, Object> params = new HashMap<>(4);
        // 姓名在进入任何渠道之前就脱敏；模板正文不含剂量 / 诊断 / 建议
        params.put("elderName", MaskUtil.name(elder == null ? null : elder.getName()));
        params.put("medicineName", task.getMedicineName());
        params.put("planTime", task.getPlanTime() == null ? null : task.getPlanTime().format(PLAN_TIME_FORMAT));
        return params;
    }

    /**
     * 短信模板参数只保留中性变量：脱敏姓名 + 药名 + 计划时间。
     * <b>禁止</b>出现 dosage / 剂量 / 诊断 / 建议类词。
     */
    private Map<String, String> buildSmsParams(Map<String, Object> contentParams) {
        Map<String, String> smsParams = new HashMap<>(4);
        smsParams.put("elderName", text(contentParams.get("elderName")));
        smsParams.put("medicineName", text(contentParams.get("medicineName")));
        smsParams.put("planTime", text(contentParams.get("planTime")));
        return smsParams;
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString();
    }

    private static String buildDedupKey(Long taskId, Long familyId, LocalDate date) {
        return "medication-missed:" + taskId + ":" + familyId + ":"
                + date.format(DateTimeFormatter.ISO_LOCAL_DATE);
    }

    private static String sanitizeError(Exception e, String phone) {
        String message = e.getClass().getSimpleName() + ": " + (e.getMessage() == null ? "" : e.getMessage());
        // 失败原因会落库：把可能被 SDK 带出的完整手机号替换成脱敏形式
        return phone == null ? message : message.replace(phone, MaskUtil.phone(phone));
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= LAST_ERROR_MAX_LENGTH ? value : value.substring(0, LAST_ERROR_MAX_LENGTH);
    }
}

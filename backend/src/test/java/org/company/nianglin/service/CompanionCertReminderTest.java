package org.company.nianglin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.company.nianglin.constant.AuditStatus;
import org.company.nianglin.constant.MessageType;
import org.company.nianglin.constant.RoleConstants;
import org.company.nianglin.entity.CompanionProfile;
import org.company.nianglin.entity.SysUser;
import org.company.nianglin.mapper.CompanionProfileMapper;
import org.company.nianglin.mapper.SysUserMapper;
import org.company.nianglin.service.impl.CompanionCertReminderService;
import org.company.nianglin.support.MybatisLambdaCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 健康证到期提醒单测（V5 · C1）。
 *
 * <p>盯三条没人会点的分支：<b>提醒窗口</b>（到期前 7 天内、已过期也算）、
 * <b>幂等</b>（同一到期日只提醒一次，靠 Redis SET NX）、
 * <b>空值不误伤</b>（存量档案未补录到期日时既不查询命中、也不该发出提醒）。</p>
 *
 * @author 银龄伴诊团队
 * @since V5
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("健康证到期提醒：窗口 / 幂等 / 收件人")
class CompanionCertReminderTest {

    private static final Long COMPANION_USER_ID = 301L;
    private static final Long ADMIN_A = 901L;
    private static final Long ADMIN_B = 902L;

    @Mock
    private CompanionProfileMapper companionProfileMapper;

    @Mock
    private SysUserMapper sysUserMapper;

    @Mock
    private MessageService messageService;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private CompanionCertReminderService service;

    @BeforeEach
    void setUp() {
        // 纯 Mockito 单测不起 Spring 容器，MyBatis-Plus 的 lambda 列名缓存是空的
        MybatisLambdaCache.warmUp();
        service = new CompanionCertReminderService(
                companionProfileMapper, sysUserMapper, messageService, redisTemplate);
    }

    @Test
    @DisplayName("窗口内：本人与全部管理员各收到一条，幂等键按到期日生成")
    void scanShouldRemindCompanionAndAdmins() {
        given(companionProfileMapper.selectList(any())).willReturn(
                List.of(approvedProfile(LocalDate.now().plusDays(3))));
        given(sysUserMapper.selectList(any())).willReturn(admins());
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .willReturn(true);

        int sent = service.scanExpiring();

        assertEquals(1, sent);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> companionParams = ArgumentCaptor.forClass(Map.class);
        verify(messageService).send(eq(COMPANION_USER_ID), eq(MessageType.HEALTH_CERT_EXPIRING),
                anyLong(), companionParams.capture());
        assertEquals("COMPANION", companionParams.getValue().get("audience"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> adminParams = ArgumentCaptor.forClass(Map.class);
        verify(messageService).sendBatch(eq(List.of(ADMIN_A, ADMIN_B)),
                eq(MessageType.HEALTH_CERT_EXPIRING), anyLong(), adminParams.capture());
        assertEquals("ADMIN", adminParams.getValue().get("audience"));
        // 管理员侧要能看出是谁的证过期：姓名必须已脱敏
        String companionName = String.valueOf(adminParams.getValue().get("companionName"));
        assertFalse(companionName.contains("李建军"), "管理员侧姓名必须脱敏：" + companionName);
    }

    @Test
    @DisplayName("幂等：同一到期日已提醒过（SET NX 未抢到）就不再发")
    void scanShouldSkipWhenAlreadyReminded() {
        given(companionProfileMapper.selectList(any())).willReturn(
                List.of(approvedProfile(LocalDate.now().plusDays(3))));
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .willReturn(false);

        int sent = service.scanExpiring();

        assertEquals(0, sent);
        verify(messageService, never()).send(anyLong(), any(MessageType.class), anyLong(), any());
        verify(messageService, never()).sendBatch(anyCollection(), any(MessageType.class), anyLong(), any());
    }

    @Test
    @DisplayName("查询口径：只取已过审、有到期日、且进入 7 天窗口的档案")
    void scanShouldQueryApprovedProfilesInsideWindow() {
        given(companionProfileMapper.selectList(any())).willReturn(List.of());

        service.scanExpiring();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<CompanionProfile>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(companionProfileMapper).selectList(captor.capture());
        String sql = captor.getValue().getSqlSegment();

        assertTrue(sql.contains("audit_status"), "只提醒已过审的陪诊员：" + sql);
        assertTrue(sql.contains("health_cert_expire"), "按健康证到期日过滤：" + sql);
        assertTrue(sql.contains("IS NOT NULL"), "未补录到期日的存量档案不进窗口：" + sql);
        assertTrue(captor.getValue().getParamNameValuePairs().values().stream()
                        .anyMatch(v -> LocalDate.now().plusDays(7).equals(v)),
                "窗口上界必须是今天 + 7 天：" + captor.getValue().getParamNameValuePairs());
    }

    @Test
    @DisplayName("空值防御：查询之外若混入无到期日的档案，跳过不发")
    void scanShouldSkipProfileWithoutExpiryDate() {
        CompanionProfile withoutExpiry = approvedProfile(null);
        given(companionProfileMapper.selectList(any())).willReturn(List.of(withoutExpiry));

        int sent = service.scanExpiring();

        assertEquals(0, sent);
        verify(messageService, never()).send(anyLong(), any(MessageType.class), anyLong(), any());
    }

    @Test
    @DisplayName("已过期的档案同样提醒一次 —— 让管理员看到谁在持证上岗")
    void scanShouldAlsoRemindAlreadyExpired() {
        given(companionProfileMapper.selectList(any())).willReturn(
                List.of(approvedProfile(LocalDate.now().minusDays(2))));
        given(sysUserMapper.selectList(any())).willReturn(admins());
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .willReturn(true);

        int sent = service.scanExpiring();

        assertEquals(1, sent);
    }

    private CompanionProfile approvedProfile(LocalDate expire) {
        CompanionProfile profile = new CompanionProfile();
        profile.setId(601L);
        profile.setUserId(COMPANION_USER_ID);
        profile.setRealName("李建军");
        profile.setAuditStatus(AuditStatus.APPROVED.name());
        profile.setHealthCertExpire(expire);
        return profile;
    }

    private List<SysUser> admins() {
        SysUser a = new SysUser();
        a.setId(ADMIN_A);
        a.setRole(RoleConstants.ADMIN);
        SysUser b = new SysUser();
        b.setId(ADMIN_B);
        b.setRole(RoleConstants.ADMIN);
        return List.of(a, b);
    }
}

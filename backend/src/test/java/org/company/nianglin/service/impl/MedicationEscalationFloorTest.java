package org.company.nianglin.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.company.nianglin.entity.MedicationTask;
import org.company.nianglin.mapper.CompanionOrderMapper;
import org.company.nianglin.mapper.ElderProfileMapper;
import org.company.nianglin.mapper.MedicationPlanMapper;
import org.company.nianglin.mapper.MedicationTaskMapper;
import org.company.nianglin.mapper.MedicineDictMapper;
import org.company.nianglin.mapper.SysUserMapper;
import org.company.nianglin.service.ElderService;
import org.company.nianglin.service.OrderService;
import org.company.nianglin.service.sms.SmsReminderService;
import org.company.nianglin.service.support.medication.ReminderDispatcher;
import org.company.nianglin.support.MybatisLambdaCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** 漏服升级时间下界单测（收口 B · P2-1）。 */
@ExtendWith(MockitoExtension.class)
@DisplayName("漏服升级扫描的时间下界（P2-1）")
class MedicationEscalationFloorTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 28, 15, 0);

    @Mock
    private MedicineDictMapper medicineMapper;
    @Mock
    private MedicationPlanMapper planMapper;
    @Mock
    private MedicationTaskMapper taskMapper;
    @Mock
    private ElderProfileMapper elderMapper;
    @Mock
    private CompanionOrderMapper orderMapper;
    @Mock
    private SysUserMapper sysUserMapper;
    @Mock
    private ElderService elderService;
    @Mock
    private ReminderDispatcher reminderDispatcher;
    @Mock
    private OrderService orderService;
    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private SmsReminderService smsReminderService;

    private MedicationServiceImpl service;

    @BeforeEach
    void setUp() {
        MybatisLambdaCache.warmUp();
        service = spy(new MedicationServiceImpl(medicineMapper, planMapper, taskMapper, elderMapper,
                orderMapper, sysUserMapper, elderService, reminderDispatcher,
                new ObjectMapper(), orderService, stringRedisTemplate, smsReminderService));
        ReflectionTestUtils.setField(service, "missThresholdMinutes", 60);
        ReflectionTestUtils.setField(service, "missScanBatch", 200);
        ReflectionTestUtils.setField(service, "escalationFloorDays", 30);
        doReturn(NOW).when(service).now();
        given(taskMapper.selectPage(any(), any())).willReturn(new Page<>(1, 10));
    }

    @Test
    @DisplayName("升级扫描带 30 天下界：30 天前的 MISSED 不再被捞起升级")
    void escalationScanShouldHaveThirtyDayFloor() {
        service.scanMissedTasks();

        LambdaQueryWrapper<MedicationTask> escalation = escalationWrapper();
        assertTrue(escalation.getSqlSegment().contains("plan_time >="),
                "升级扫描必须有时间下界，实际：" + escalation.getSqlSegment());
        assertTrue(escalation.getParamNameValuePairs().containsValue(NOW.minusDays(30)),
                "下界值应为 now - 30 天，实际参数：" + escalation.getParamNameValuePairs());
    }

    @Test
    @DisplayName("下界可配置：改成 7 天后行为随配置变化")
    void floorShouldBeConfigurable() {
        ReflectionTestUtils.setField(service, "escalationFloorDays", 7);

        service.scanMissedTasks();

        LambdaQueryWrapper<MedicationTask> escalation = escalationWrapper();
        // 先取 SQL 触发 MyBatis-Plus 的参数归一化，否则 paramNameValuePairs 还是空的
        assertTrue(escalation.getSqlSegment().contains("plan_time >="));
        assertTrue(escalation.getParamNameValuePairs().containsValue(NOW.minusDays(7)),
                "下界应随配置变化，实际参数：" + escalation.getParamNameValuePairs());
    }

    @Test
    @DisplayName("判漏服扫描①段不受下界影响（否则旧 PENDING 永远停在 PENDING）")
    void claimScanShouldNotBeAffected() {
        service.scanMissedTasks();

        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<LambdaQueryWrapper<MedicationTask>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(taskMapper, times(2)).selectPage(any(), captor.capture());
        String claimSql = captor.getAllValues().get(0).getSqlSegment();
        assertFalse(claimSql.contains("plan_time >="),
                "判漏服扫描不能加下界，实际：" + claimSql);
        assertTrue(claimSql.contains("plan_time <"), "判漏服扫描仍按超时阈值捞任务");
    }

    private LambdaQueryWrapper<MedicationTask> escalationWrapper() {
        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<LambdaQueryWrapper<MedicationTask>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(taskMapper, times(2)).selectPage(any(), captor.capture());
        return captor.getAllValues().get(1);
    }
}

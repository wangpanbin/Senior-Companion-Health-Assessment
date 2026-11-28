package org.company.nianglin.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.company.nianglin.constant.MealRelation;
import org.company.nianglin.constant.MedicationPlanStatus;
import org.company.nianglin.entity.ElderProfile;
import org.company.nianglin.entity.MedicationPlan;
import org.company.nianglin.entity.MedicationTask;
import org.company.nianglin.mapper.CompanionOrderMapper;
import org.company.nianglin.mapper.ElderProfileMapper;
import org.company.nianglin.mapper.MedicationPlanMapper;
import org.company.nianglin.mapper.MedicationTaskMapper;
import org.company.nianglin.mapper.MedicineDictMapper;
import org.company.nianglin.mapper.SysUserMapper;
import org.company.nianglin.security.LoginUser;
import org.company.nianglin.service.ElderService;
import org.company.nianglin.service.OrderService;
import org.company.nianglin.service.sms.SmsReminderService;
import org.company.nianglin.service.support.medication.ReminderDispatcher;
import org.company.nianglin.support.MybatisLambdaCache;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

/**
 * 停药 → 重新启用单测（收口 B · P1-5）。
 *
 * <p>核心回归是「停用 → 启用 → 当天未到点任务重新生成」。唯一键与 {@code @TableLogic} 的
 * 交互属于 schema 行为，单测只能证明「删除走硬删、重建走 P1-4 同一份逻辑」，
 * 唯一键本身由 commit body 中的人工 SQL 验证覆盖。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("停用与重新启用用药计划（P1-5）")
class MedicationPlanEnableTest {

    private static final Long ELDER_ID = 401L;
    private static final Long FAMILY_ID = 101L;
    private static final Long PLAN_ID = 7001L;
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 28);

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
        ReflectionTestUtils.setField(service, "maxCalendarDays", 31);
        ReflectionTestUtils.setField(service, "generateGraceMinutes", 1);
        loginAsFamily();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("启用已停用计划：条件更新 DISABLED→ACTIVE")
    void enableDisabledPlanShouldSetActive() {
        stubCommon();
        doReturn(TODAY).when(service).today();
        doReturn(false).when(service).isPast(any(LocalDateTime.class));
        given(planMapper.selectById(PLAN_ID)).willReturn(plan(MedicationPlanStatus.DISABLED.name()));
        given(planMapper.update(any(), any())).willReturn(1);

        service.enablePlan(PLAN_ID);

        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<MedicationPlan>> captor =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper.class);
        verify(planMapper).update(any(), captor.capture());
        String sql = captor.getValue().getSqlSegment();
        org.junit.jupiter.api.Assertions.assertTrue(sql.contains("status"),
                "必须按状态条件更新，实际：" + sql);
    }

    @Test
    @DisplayName("核心回归：停用 → 启用 → 当天未到点任务重新生成")
    void enableShouldRegenerateTodayFutureTasks() {
        stubCommon();
        stubEveningTime();
        given(planMapper.selectById(PLAN_ID)).willReturn(plan(MedicationPlanStatus.DISABLED.name()));
        given(planMapper.update(any(), any())).willReturn(1);

        service.enablePlan(PLAN_ID);

        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<MedicationTask> captor = ArgumentCaptor.forClass(MedicationTask.class);
        verify(taskMapper).insert(captor.capture());
        assertEquals(22, captor.getValue().getPlanTime().getHour());
        assertEquals(org.company.nianglin.constant.MedicationTaskStatus.PENDING.name(),
                captor.getValue().getStatus());
    }

    @Test
    @DisplayName("启用后当天已过时点不补生成（与 P1-4 同口径）")
    void enableShouldNotRegeneratePastTimePoint() {
        stubCommon();
        given(planMapper.selectById(PLAN_ID)).willReturn(plan(MedicationPlanStatus.DISABLED.name()));
        given(planMapper.update(any(), any())).willReturn(1);
        stubEveningTime();

        service.enablePlan(PLAN_ID);

        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<MedicationTask> captor = ArgumentCaptor.forClass(MedicationTask.class);
        verify(taskMapper).insert(captor.capture());
        assertEquals(22, captor.getValue().getPlanTime().getHour(), "08:00 已过，不该补生成");
    }

    @Test
    @DisplayName("重复启用幂等：已 ACTIVE 时不更新、不重建")
    void repeatedEnableShouldBeIdempotent() {
        given(planMapper.selectById(PLAN_ID)).willReturn(plan(MedicationPlanStatus.ACTIVE.name()));
        given(elderService.requireAccessible(ELDER_ID)).willReturn(elder());

        service.enablePlan(PLAN_ID);

        verify(planMapper, never()).update(any(), any());
        verify(taskMapper, never()).insert(any(MedicationTask.class));
    }

    @Test
    @DisplayName("启用不存在的计划 → 404")
    void enableMissingPlanShould404() {
        given(planMapper.selectById(PLAN_ID)).willReturn(null);

        org.junit.jupiter.api.Assertions.assertThrows(
                org.company.nianglin.exception.BusinessException.class, () -> service.enablePlan(PLAN_ID));
    }

    @Test
    @DisplayName("停用未来 PENDING 任务走物理删除，不走软删（唯一键不再被占）")
    void disableShouldHardDeleteFuturePendingTasks() {
        MedicationPlan active = plan(MedicationPlanStatus.ACTIVE.name());
        given(planMapper.selectById(PLAN_ID)).willReturn(active);
        given(elderService.requireAccessible(ELDER_ID)).willReturn(elder());
        MedicationTask future = new MedicationTask();
        future.setId(9001L);
        future.setStatus(org.company.nianglin.constant.MedicationTaskStatus.PENDING.name());
        given(taskMapper.selectList(any())).willReturn(List.of(future));
        given(taskMapper.deleteFuturePendingPhysically(any(), any())).willReturn(1);
        given(smsReminderService.voidUnsentForTasks(any(), any())).willReturn(1);
        given(planMapper.update(any(), any())).willReturn(1);

        service.disablePlan(PLAN_ID);

        verify(taskMapper).deleteFuturePendingPhysically(org.mockito.ArgumentMatchers.eq(PLAN_ID), any(LocalDateTime.class));
        // 软删调用保留只为兼容既有断言；真正释放唯一键的是紧随其后的物理删除
        verify(smsReminderService).voidUnsentForTasks(List.of(9001L), "plan-disabled");
    }

    /* ================================================================== */
    /* 夹具                                                                */
    /* ================================================================== */

    /** enable 成功且会触发重建时共用的桩 */
    private void stubCommon() {
        given(elderService.requireAccessible(ELDER_ID)).willReturn(elder());
        given(taskMapper.selectList(any())).willReturn(List.of());
    }

    /** 今天 21:00：08:00 已过、22:00 未到 */
    private void stubEveningTime() {
        doReturn(TODAY).when(service).today();
        doAnswer(inv -> ((LocalDateTime) inv.getArgument(0)).isBefore(TODAY.atTime(21, 0)))
                .when(service).isPast(any(LocalDateTime.class));
    }

    private void loginAsFamily() {
        LoginUser loginUser = new LoginUser(FAMILY_ID, "family101", org.company.nianglin.constant.RoleConstants.FAMILY,
                0, "jti-p1-5", System.currentTimeMillis() + 60000);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, loginUser.authorities()));
    }

    private MedicationPlan plan(String status) {
        MedicationPlan plan = new MedicationPlan();
        plan.setId(PLAN_ID);
        plan.setElderId(ELDER_ID);
        plan.setMedicineId(801L);
        plan.setMedicineName("苯磺酸氨氯地平片");
        plan.setDosage("1 片");
        plan.setFrequency(2);
        plan.setTimePoints("[\"08:00\",\"22:00\"]");
        plan.setMealRelation(MealRelation.BEFORE_MEAL.name());
        plan.setStartDate(TODAY.minusDays(1));
        plan.setEndDate(null);
        plan.setStatus(status);
        return plan;
    }

    private ElderProfile elder() {
        ElderProfile elder = new ElderProfile();
        elder.setId(ELDER_ID);
        elder.setUserId(201L);
        elder.setName("测试老人");
        return elder;
    }
}

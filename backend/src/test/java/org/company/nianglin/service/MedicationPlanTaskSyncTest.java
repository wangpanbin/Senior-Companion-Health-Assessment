package org.company.nianglin.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.company.nianglin.constant.MealRelation;
import org.company.nianglin.constant.MedicationPlanStatus;
import org.company.nianglin.constant.MedicationTaskStatus;
import org.company.nianglin.constant.RoleConstants;
import org.company.nianglin.dto.MedicationPlanCreateDTO;
import org.company.nianglin.dto.MedicationPlanUpdateDTO;
import org.company.nianglin.entity.ElderProfile;
import org.company.nianglin.entity.MedicationPlan;
import org.company.nianglin.entity.MedicationTask;
import org.company.nianglin.entity.MedicineDict;
import org.company.nianglin.mapper.CompanionOrderMapper;
import org.company.nianglin.mapper.ElderProfileMapper;
import org.company.nianglin.mapper.MedicationPlanMapper;
import org.company.nianglin.mapper.MedicationTaskMapper;
import org.company.nianglin.mapper.MedicineDictMapper;
import org.company.nianglin.mapper.SysUserMapper;
import org.company.nianglin.security.LoginUser;
import org.company.nianglin.service.ElderService;
import org.company.nianglin.service.OrderService;
import org.company.nianglin.service.impl.MedicationServiceImpl;
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
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

/**
 * 计划 ↔ 任务同源单测（收口 B · P1-4）。
 *
 * <p>时间用 {@code spy} 覆盖 {@code today()} / {@code isPast()} 两个 seam，
 * 不引入全局 {@code Clock}。断言的是「最终 insert 了哪些任务」「删了哪些任务」，
 * 不反射私有方法、不连真库。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("用药计划与当天任务同源（P1-4）")
class MedicationPlanTaskSyncTest {

    private static final Long ELDER_ID = 401L;
    private static final Long FAMILY_ID = 101L;
    private static final Long PLAN_ID = 7001L;
    private static final Long MEDICINE_ID = 801L;
    private static final Long NEW_MEDICINE_ID = 802L;
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
    @DisplayName("晚上建计划：当天已过时点不生成任务（不会凭空两条漏服）")
    void eveningCreateShouldSkipPastTimePoint() {
        doReturn(TODAY).when(service).today();
        // 当前 21:00：08:00 已过，22:00 未到
        doAnswer(inv -> ((LocalDateTime) inv.getArgument(0)).isBefore(TODAY.atTime(21, 0)))
                .when(service).isPast(any(LocalDateTime.class));
        stubCreate();

        service.createPlan(createDto("2026-11-28", null, "08:00", "22:00"));

        List<MedicationTask> inserted = captureInsertedTasks();
        assertEquals(1, inserted.size(), "已过时点不该生成任务");
        assertEquals(22, inserted.get(0).getPlanTime().getHour());
    }

    @Test
    @DisplayName("早上建计划：当天全部时点照常生成（保护现状）")
    void morningCreateShouldGenerateAllTimePoints() {
        doReturn(TODAY).when(service).today();
        doReturn(false).when(service).isPast(any(LocalDateTime.class));
        stubCreate();

        service.createPlan(createDto("2026-11-28", null, "08:00", "20:00"));

        assertEquals(2, captureInsertedTasks().size());
    }

    @Test
    @DisplayName("补跑历史日期：即使 isPast 恒为 true 也生成全部时点（那是补数据）")
    void backfillPastDateShouldGenerateAllTimePoints() {
        doReturn(TODAY).when(service).today();
        MedicationPlan plan = plan("2026-11-20", null, "08:00", "20:00");
        given(planMapper.selectList(any())).willReturn(List.of(plan));
        given(taskMapper.selectList(any())).willReturn(List.of());

        service.generateDailyTasks(LocalDate.of(2026, 11, 20));

        assertEquals(2, captureInsertedTasks().size());
        // 历史日期补数据根本不该问「是否已过」——这也证明跳过逻辑只对今天生效
        org.mockito.Mockito.verify(service, org.mockito.Mockito.never()).isPast(any(LocalDateTime.class));
    }

    @Test
    @DisplayName("改药名：当天未来 PENDING 任务重建为新药名，并作废未发送回执")
    void updatePlanShouldRebuildFuturePendingTasks() {
        doReturn(TODAY).when(service).today();
        doReturn(false).when(service).isPast(any(LocalDateTime.class));
        stubUpdate();
        given(medicineMapper.selectById(NEW_MEDICINE_ID)).willReturn(dict(NEW_MEDICINE_ID, "新药名"));

        MedicationPlanUpdateDTO dto = new MedicationPlanUpdateDTO();
        dto.setMedicineId(NEW_MEDICINE_ID);
        dto.setTimePoints(List.of("09:00", "22:00"));
        dto.setFrequency(2);
        service.updatePlan(PLAN_ID, dto);

        List<MedicationTask> inserted = captureInsertedTasks();
        assertEquals(2, inserted.size());
        assertEquals("新药名", inserted.get(0).getMedicineName());
        verify(taskMapper).delete(any());
        verify(smsReminderService).voidUnsentForTasks(List.of(9001L), "plan-updated");
    }

    @Test
    @DisplayName("改时间点：已过时点与已确认任务不被删除（只删未来 PENDING）")
    void updatePlanShouldNotDeletePastOrConfirmedTasks() {
        doReturn(TODAY).when(service).today();
        doReturn(false).when(service).isPast(any(LocalDateTime.class));
        stubUpdate();

        MedicationPlanUpdateDTO dto = new MedicationPlanUpdateDTO();
        dto.setTimePoints(List.of("09:00", "22:00"));
        dto.setFrequency(2);
        service.updatePlan(PLAN_ID, dto);

        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<MedicationTask>> captor =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper.class);
        verify(taskMapper).delete(captor.capture());
        String sql = captor.getValue().getSqlSegment();
        org.junit.jupiter.api.Assertions.assertTrue(sql.contains("status = #{ew.paramNameValuePairs.MPGENVAL"),
                "删除必须限定 PENDING，实际：" + sql);
        org.junit.jupiter.api.Assertions.assertTrue(sql.contains("plan_time >"),
                "删除必须限定未来时点，实际：" + sql);
    }

    /* ================================================================== */
    /* 夹具                                                                */
    /* ================================================================== */

    private void loginAsFamily() {
        LoginUser loginUser = new LoginUser(FAMILY_ID, "family101", RoleConstants.FAMILY, 0,
                "jti-p1-4", System.currentTimeMillis() + 60000);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, loginUser.authorities()));
    }

    private void stubCreate() {
        given(elderService.requireAccessible(ELDER_ID)).willReturn(elder());
        given(medicineMapper.selectById(MEDICINE_ID)).willReturn(dict(MEDICINE_ID, "原药名"));
        given(planMapper.insert(any(MedicationPlan.class))).willAnswer(invocation -> {
            invocation.getArgument(0, MedicationPlan.class).setId(PLAN_ID);
            return 1;
        });
        given(taskMapper.selectList(any())).willReturn(List.of());
    }

    private void stubUpdate() {
        given(planMapper.selectById(PLAN_ID)).willReturn(plan("2026-11-28", null, "08:00", "20:00"));
        given(elderService.requireAccessible(ELDER_ID)).willReturn(elder());
        MedicationTask future = new MedicationTask();
        future.setId(9001L);
        future.setPlanId(PLAN_ID);
        future.setPlanTime(TODAY.atTime(20, 0));
        future.setStatus(MedicationTaskStatus.PENDING.name());
        given(taskMapper.selectList(any())).willReturn(List.of(future));
        given(taskMapper.delete(any())).willReturn(1);
        given(smsReminderService.voidUnsentForTasks(any(), any())).willReturn(1);
        given(planMapper.update(any(), any())).willReturn(1);
    }

    private List<MedicationTask> captureInsertedTasks() {
        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<MedicationTask> captor = ArgumentCaptor.forClass(MedicationTask.class);
        verify(taskMapper, org.mockito.Mockito.atLeastOnce()).insert(captor.capture());
        return captor.getAllValues();
    }

    private MedicationPlanCreateDTO createDto(String start, String end, String... points) {
        MedicationPlanCreateDTO dto = new MedicationPlanCreateDTO();
        dto.setElderId(ELDER_ID);
        dto.setMedicineId(MEDICINE_ID);
        dto.setDosage("1 片");
        dto.setFrequency(points.length);
        dto.setTimePoints(List.of(points));
        dto.setStartDate(start);
        dto.setEndDate(end);
        dto.setMealRelation(MealRelation.BEFORE_MEAL.name());
        return dto;
    }

    private MedicationPlan plan(String start, String end, String... points) {
        MedicationPlan plan = new MedicationPlan();
        plan.setId(PLAN_ID);
        plan.setElderId(ELDER_ID);
        plan.setMedicineId(MEDICINE_ID);
        plan.setMedicineName("原药名");
        plan.setDosage("1 片");
        plan.setFrequency(points.length);
        plan.setTimePoints("[\"" + String.join("\",\"", points) + "\"]");
        plan.setMealRelation(MealRelation.BEFORE_MEAL.name());
        plan.setStartDate(LocalDate.parse(start));
        plan.setEndDate(end == null ? null : LocalDate.parse(end));
        plan.setStatus(MedicationPlanStatus.ACTIVE.name());
        return plan;
    }

    private ElderProfile elder() {
        ElderProfile elder = new ElderProfile();
        elder.setId(ELDER_ID);
        elder.setUserId(201L);
        elder.setName("测试老人");
        return elder;
    }

    private MedicineDict dict(Long id, String name) {
        MedicineDict dict = new MedicineDict();
        dict.setId(id);
        dict.setName(name);
        return dict;
    }
}

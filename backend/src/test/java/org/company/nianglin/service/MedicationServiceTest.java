package org.company.nianglin.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.company.nianglin.common.ResultCode;
import org.company.nianglin.constant.RoleConstants;
import org.company.nianglin.dto.MedicationCalendarQuery;
import org.company.nianglin.dto.MedicationPlanCreateDTO;
import org.company.nianglin.dto.MedicationTaskConfirmDTO;
import org.company.nianglin.entity.ElderProfile;
import org.company.nianglin.entity.MedicationPlan;
import org.company.nianglin.entity.MedicationTask;
import org.company.nianglin.entity.MedicineDict;
import org.company.nianglin.entity.SysUser;
import org.company.nianglin.exception.BusinessException;
import org.company.nianglin.mapper.CompanionOrderMapper;
import org.company.nianglin.mapper.ElderProfileMapper;
import org.company.nianglin.mapper.FamilyElderRelationMapper;
import org.company.nianglin.mapper.MedicationPlanMapper;
import org.company.nianglin.mapper.MedicationTaskMapper;
import org.company.nianglin.mapper.MedicineDictMapper;
import org.company.nianglin.mapper.SysUserMapper;
import org.company.nianglin.security.LoginUser;
import org.company.nianglin.service.impl.MedicationServiceImpl;
import org.company.nianglin.support.MybatisLambdaCache;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 用药管理服务单测（M6）。
 *
 * <p>M6 的风险不在「能建出计划」，而在几处<b>一旦写错就没人会发现</b>的地方：</p>
 *
 * <ol>
 *   <li><b>补记与漏服率的关系</b>：漏服后补记必须把 {@code was_missed} 置 1。
 *       如果只改状态不置标记，家属会看到「漏服率 0」——
 *       而漏服率正是家长和答辩老师都会看的指标，算错等于用系统的账错误地表扬家属。</li>
 *   <li><b>并发确认的兜底</b>：条件更新影响行数为 0 时有<b>两种</b>真实原因
 *       （别人已确认 / 别人已判漏服），必须重新读一次状态再决定报 5003 还是 409。
 *       一律报「已确认」会把「状态被别人改了」这个事实藏起来。</li>
 *   <li><b>时间点与频次的一致性</b>：{@code ["08:00","08:00"]} + {@code frequency=2}
 *       会在同一天生成两条完全相同的任务，唯一索引让第二条静默失败 ——
 *       用户在日历上只看到一条，无法解释。这里必须去重后再比数量。</li>
 *   <li><b>归属校验的三档</b>：陪诊员走「是否有在途订单」，其余角色走 M3 的
 *       {@code requireAccessible}。两条路必须都被走到。</li>
 *   <li><b>漏服扫描的「认领」语义</b>：{@code affected = 0} 说明这条刚被别人处理，
 *       必须跳过而不是把它算成漏服。</li>
 * </ol>
 *
 * <p>纯单测（不起 Spring 容器），依赖 {@link MybatisLambdaCache#warmUp()} 预热
 * lambda 列名缓存 —— 不预热的话 {@code wrapper.set(...)} 会立刻抛异常。</p>
 *
 * @author 银龄伴诊团队
 * @since M6
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("用药管理服务：补记标记 / 并发兜底 / 时间点一致 / 扫描认领")
class MedicationServiceTest {

    private static final Long ELDER_ID = 401L;
    private static final Long FAMILY_ID = 101L;
    private static final Long COMPANION_ID = 301L;
    private static final Long TASK_ID = 20002L;
    private static final Long MEDICINE_ID = 801L;

    @Mock
    private MedicineDictMapper medicineMapper;

    @Mock
    private MedicationPlanMapper planMapper;

    @Mock
    private MedicationTaskMapper taskMapper;

    @Mock
    private ElderProfileMapper elderMapper;

    @Mock
    private FamilyElderRelationMapper relationMapper;

    @Mock
    private CompanionOrderMapper orderMapper;

    @Mock
    private SysUserMapper sysUserMapper;

    @Mock
    private ElderService elderService;

    @Mock
    private MessageService messageService;

    private MedicationServiceImpl service;

    @BeforeEach
    void setUp() {
        MybatisLambdaCache.warmUp();
        service = new MedicationServiceImpl(medicineMapper, planMapper, taskMapper, elderMapper,
                relationMapper, orderMapper, sysUserMapper, elderService, messageService,
                new ObjectMapper());
        // @Value 字段在纯单测里不会被注入，不手动设就是 0：
        // 日历上限 0 会让「查一天」都被拒，阈值 0 会让所有 PENDING 任务都被判漏服
        ReflectionTestUtils.setField(service, "missThresholdMinutes", 60);
        ReflectionTestUtils.setField(service, "missScanBatch", 200);
        ReflectionTestUtils.setField(service, "maxCalendarDays", 31);
        loginAs(RoleConstants.FAMILY, FAMILY_ID);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    /* ================================================================== */
    /* 1 · 确认服药：状态机 + 补记标记                                       */
    /* ================================================================== */

    @Test
    @DisplayName("确认服药 · 任务不存在 → 404，且不做任何写入")
    void confirmShouldRejectMissingTask() {
        given(taskMapper.selectById(TASK_ID)).willReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.confirm(TASK_ID, new MedicationTaskConfirmDTO()));

        assertEquals(ResultCode.NOT_FOUND.getCode(), ex.getCode());
        verify(taskMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("确认服药 · 已确认的任务 → 5003（重复点「已服用」要有明确提示）")
    void confirmShouldRejectAlreadyTaken() {
        given(taskMapper.selectById(TASK_ID)).willReturn(task("TAKEN", ELDER_ID));
        given(elderService.requireAccessible(ELDER_ID)).willReturn(elder());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.confirm(TASK_ID, new MedicationTaskConfirmDTO()));

        assertEquals(ResultCode.MEDICATION_TASK_CONFIRMED.getCode(), ex.getCode());
        verify(taskMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("确认服药 · 未到该老人 → 2006（在状态判断之前就拦下）")
    void confirmShouldRejectUnrelatedElder() {
        given(taskMapper.selectById(TASK_ID)).willReturn(task("PENDING", ELDER_ID));
        given(elderService.requireAccessible(ELDER_ID))
                .willThrow(new BusinessException(ResultCode.NO_PERMISSION_FOR_ELDER));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.confirm(TASK_ID, new MedicationTaskConfirmDTO()));

        assertEquals(ResultCode.NO_PERMISSION_FOR_ELDER.getCode(), ex.getCode());
        verify(taskMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("确认服药 · 漏服后补记：状态置 TAKEN，且 was_missed 必须置 1")
    void confirmShouldFlagWasMissedOnLateConfirm() {
        given(taskMapper.selectById(TASK_ID)).willReturn(task("MISSED", ELDER_ID), task("TAKEN", ELDER_ID));
        given(elderService.requireAccessible(ELDER_ID)).willReturn(elder());
        given(sysUserMapper.selectById(FAMILY_ID)).willReturn(user(FAMILY_ID, "王小明"));
        given(taskMapper.update(any(), any())).willReturn(1);

        MedicationTaskConfirmDTO dto = new MedicationTaskConfirmDTO();
        dto.setRemark("发现漏服后补记");
        service.confirm(TASK_ID, dto);

        String setSql = captureTaskUpdate().getSqlSet();
        assertTrue(setSql.contains("status"), setSql);
        assertTrue(setSql.contains("was_missed"),
                "漏服后补记必须置 was_missed，否则漏服率会把这一条当成按时服用：" + setSql);
    }

    @Test
    @DisplayName("确认服药 · 按时确认：只改状态，不许往 was_missed 里写值")
    void confirmOnTimeShouldNotTouchWasMissed() {
        given(taskMapper.selectById(TASK_ID)).willReturn(task("PENDING", ELDER_ID), task("TAKEN", ELDER_ID));
        given(elderService.requireAccessible(ELDER_ID)).willReturn(elder());
        given(sysUserMapper.selectById(FAMILY_ID)).willReturn(user(FAMILY_ID, "王小明"));
        given(taskMapper.update(any(), any())).willReturn(1);
        service.confirm(TASK_ID, new MedicationTaskConfirmDTO());

        String setSql = captureTaskUpdate().getSqlSet();
        assertTrue(setSql.contains("status"), setSql);
        assertFalse(setSql.contains("was_missed"),
                "按时确认不该写 was_missed（写入 0 会覆盖历史标记）：" + setSql);
    }

    @Test
    @DisplayName("确认服药 · 条件更新未命中且最新状态已是 TAKEN → 5003（别人刚确认）")
    void confirmShouldReport5003WhenSomeoneElseConfirmed() {
        // 第一次读是 PENDING，写到一半被别人抢先；重读时已是 TAKEN
        given(taskMapper.selectById(TASK_ID)).willReturn(task("PENDING", ELDER_ID), task("TAKEN", ELDER_ID));
        given(elderService.requireAccessible(ELDER_ID)).willReturn(elder());
        given(taskMapper.update(any(), any())).willReturn(0);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.confirm(TASK_ID, new MedicationTaskConfirmDTO()));

        assertEquals(ResultCode.MEDICATION_TASK_CONFIRMED.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("确认服药 · 条件更新未命中且最新状态不是 TAKEN → 409（状态被扫成漏服，不是「已确认」）")
    void confirmShouldReportConflictWhenStatusChangedByScan() {
        given(taskMapper.selectById(TASK_ID)).willReturn(task("PENDING", ELDER_ID), task("MISSED", ELDER_ID));
        given(elderService.requireAccessible(ELDER_ID)).willReturn(elder());
        given(taskMapper.update(any(), any())).willReturn(0);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.confirm(TASK_ID, new MedicationTaskConfirmDTO()));

        // 这里若也报 5003，用户会以为「别人替我确认了」，而事实是漏服扫描刚判它漏服
        assertEquals(ResultCode.CONFLICT.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("确认服药 · 实际服药时间填到未来 → 400（不接受「还没吃就说吃了」）")
    void confirmShouldRejectFutureConfirmTime() {
        given(taskMapper.selectById(TASK_ID)).willReturn(task("PENDING", ELDER_ID));
        given(elderService.requireAccessible(ELDER_ID)).willReturn(elder());

        MedicationTaskConfirmDTO dto = new MedicationTaskConfirmDTO();
        dto.setConfirmTime(LocalDateTime.now().plusHours(2).toString());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.confirm(TASK_ID, dto));

        assertEquals(ResultCode.PARAM_ERROR.getCode(), ex.getCode());
        verify(taskMapper, never()).update(any(), any());
    }

    /* ================================================================== */
    /* 2 · 新增计划：时间点与频次的一致性                                     */
    /* ================================================================== */

    @Test
    @DisplayName("新增计划 · 时间点数量与每日次数不符 → 400（否则日历上会凭空少一次）")
    void createPlanShouldRejectFrequencyMismatch() {
        given(elderService.requireAccessible(ELDER_ID)).willReturn(elder());
        given(medicineMapper.selectById(MEDICINE_ID)).willReturn(medicine());

        MedicationPlanCreateDTO dto = createPlanDto(List.of("08:00", "20:00"), 3);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.createPlan(dto));
        assertEquals(ResultCode.PARAM_ERROR.getCode(), ex.getCode());
        verify(planMapper, never()).insert(any(MedicationPlan.class));
    }

    @Test
    @DisplayName("新增计划 · 重复时间点被去重后再比数量 → 400（不能靠唯一索引静默吞掉一条）")
    void createPlanShouldRejectDuplicatedTimePoints() {
        given(elderService.requireAccessible(ELDER_ID)).willReturn(elder());
        given(medicineMapper.selectById(MEDICINE_ID)).willReturn(medicine());

        MedicationPlanCreateDTO dto = createPlanDto(List.of("08:00", "08:00"), 2);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.createPlan(dto));
        assertEquals(ResultCode.PARAM_ERROR.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("新增计划 · 时间点格式非法 → 400")
    void createPlanShouldRejectBadTimePointFormat() {
        given(elderService.requireAccessible(ELDER_ID)).willReturn(elder());
        given(medicineMapper.selectById(MEDICINE_ID)).willReturn(medicine());

        MedicationPlanCreateDTO dto = createPlanDto(List.of("25:00"), 1);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.createPlan(dto));
        assertEquals(ResultCode.PARAM_ERROR.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("新增计划 · 结束日期早于开始日期 → 5002")
    void createPlanShouldRejectInvertedDateRange() {
        given(elderService.requireAccessible(ELDER_ID)).willReturn(elder());
        given(medicineMapper.selectById(MEDICINE_ID)).willReturn(medicine());

        MedicationPlanCreateDTO dto = createPlanDto(List.of("08:00"), 1);
        dto.setStartDate("2026-09-20");
        dto.setEndDate("2026-09-01");

        BusinessException ex = assertThrows(BusinessException.class, () -> service.createPlan(dto));
        assertEquals(ResultCode.MEDICATION_PLAN_INVALID.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("新增计划 · 药品不在字典里 → 5001（不允许挂一个不存在的药）")
    void createPlanShouldRejectUnknownMedicine() {
        given(elderService.requireAccessible(ELDER_ID)).willReturn(elder());
        given(medicineMapper.selectById(MEDICINE_ID)).willReturn(null);

        MedicationPlanCreateDTO dto = createPlanDto(List.of("08:00"), 1);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.createPlan(dto));
        assertEquals(ResultCode.MEDICINE_NOT_FOUND.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("新增计划 · 时间点被排序后落库（08:00 一定排在 20:00 前面）")
    void createPlanShouldPersistSortedTimePoints() {
        given(elderService.requireAccessible(ELDER_ID)).willReturn(elder());
        given(medicineMapper.selectById(MEDICINE_ID)).willReturn(medicine());

        service.createPlan(createPlanDto(List.of("20:00", "08:00"), 2));

        ArgumentCaptor<MedicationPlan> captor = ArgumentCaptor.forClass(MedicationPlan.class);
        verify(planMapper).insert(captor.capture());
        assertEquals("[\"08:00\",\"20:00\"]", captor.getValue().getTimePoints(),
                "时间点必须去重并按时间排序后落库，否则展示顺序会随录入顺序漂移");
        assertEquals("ACTIVE", captor.getValue().getStatus());
    }

    /* ================================================================== */
    /* 3 · 服药日历：区间上限                                                */
    /* ================================================================== */

    @Test
    @DisplayName("服药日历 · 区间超过 31 天 → 400（否则日历接口会变成批量导出的口子）")
    void calendarShouldRejectTooWideRange() {
        given(elderService.requireAccessible(ELDER_ID)).willReturn(elder());

        MedicationCalendarQuery query = new MedicationCalendarQuery();
        query.setElderId(ELDER_ID);
        query.setStartDate("2026-01-01");
        query.setEndDate("2026-12-31");

        BusinessException ex = assertThrows(BusinessException.class, () -> service.calendar(query));
        assertEquals(ResultCode.PARAM_ERROR.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("服药日历 · 起止同一天是合法的（用 daysBetween 差值算会误判为 0 天）")
    void calendarShouldAllowSingleDayRange() {
        given(elderService.requireAccessible(ELDER_ID)).willReturn(elder());
        given(taskMapper.selectList(any())).willReturn(List.of());

        MedicationCalendarQuery query = new MedicationCalendarQuery();
        query.setElderId(ELDER_ID);
        query.setStartDate("2026-09-15");
        query.setEndDate("2026-09-15");

        assertNotNull(service.calendar(query));
    }

    /* ================================================================== */
    /* 4 · 定时任务：生成与漏服扫描                                          */
    /* ================================================================== */

    @Test
    @DisplayName("生成任务 · 当日无生效计划 → 返回 0，不产生任何写入")
    void generateShouldDoNothingWhenNoActivePlan() {
        given(planMapper.selectList(any())).willReturn(List.of());

        assertEquals(0, service.generateDailyTasks(LocalDate.of(2026, 9, 16)));
        verify(taskMapper, never()).insert(any(MedicationTask.class));
    }

    @Test
    @DisplayName("生成任务 · 已有同 (planId, planTime) 的记录时跳过，只补缺的那一条")
    void generateShouldSkipExistingTask() {
        MedicationPlan plan = new MedicationPlan();
        plan.setId(10001L);
        plan.setElderId(ELDER_ID);
        plan.setMedicineId(MEDICINE_ID);
        plan.setMedicineName("苯磺酸氨氯地平片");
        plan.setDosage("1 片");
        plan.setMealRelation("AFTER_MEAL");
        plan.setTimePoints("[\"08:00\",\"20:00\"]");

        LocalDate date = LocalDate.of(2026, 9, 16);
        MedicationTask existing = new MedicationTask();
        existing.setId(1L);
        existing.setPlanTime(date.atTime(8, 0));

        given(planMapper.selectList(any())).willReturn(List.of(plan));
        given(taskMapper.selectList(any())).willReturn(new ArrayList<>(List.of(existing)));
        given(taskMapper.insert(any(MedicationTask.class))).willReturn(1);

        assertEquals(1, service.generateDailyTasks(date), "08:00 已存在，只应补出 20:00 那一条");
        verify(taskMapper).insert(any(MedicationTask.class));
    }

    @Test
    @DisplayName("漏服扫描 · 没有超时任务 → 返回 0，且不推站内信")
    void scanShouldDoNothingWhenNothingOverdue() {
        given(taskMapper.selectPage(any(), any())).willReturn(new Page<>());

        assertEquals(0, service.scanMissedTasks());
        verify(messageService, never()).sendBatch(any(), any(), any(), any());
    }

    @Test
    @DisplayName("漏服扫描 · 条件更新影响 0 行时跳过（说明这条刚被家属确认，不能算漏服）")
    void scanShouldSkipTaskClaimedByOthers() {
        MedicationTask overdueTask = task("PENDING", ELDER_ID);
        overdueTask.setId(TASK_ID);
        Page<MedicationTask> page = new Page<>();
        page.setRecords(List.of(overdueTask));

        given(taskMapper.selectPage(any(), any())).willReturn(page);
        given(taskMapper.update(any(), any())).willReturn(0);

        assertEquals(0, service.scanMissedTasks(),
                "affected = 0 意味着「认领失败」，这条不该计入漏服");
        verify(messageService, never()).sendBatch(any(), any(), any(), any());
    }

    @Test
    @DisplayName("漏服扫描 · 认领成功才计入漏服，并把任务置为 MISSED")
    void scanShouldMarkMissedOnSuccessfulClaim() {
        MedicationTask overdueTask = task("PENDING", ELDER_ID);
        overdueTask.setId(TASK_ID);
        Page<MedicationTask> page = new Page<>();
        page.setRecords(List.of(overdueTask));

        given(taskMapper.selectPage(any(), any())).willReturn(page);
        given(taskMapper.update(any(), any())).willReturn(1);
        given(elderMapper.selectById(ELDER_ID)).willReturn(elder());

        assertEquals(1, service.scanMissedTasks());

        String setSql = captureTaskUpdate(TASK_ID).getSqlSet();
        assertTrue(setSql.contains("status"), setSql);
    }

    /* ================================================================== */
    /* 5 · 归属校验：陪诊员与家属走的是两条不同的路                            */
    /* ================================================================== */

    @Test
    @DisplayName("归属 · 家属走 M3 的 requireAccessible（不另写一套判断）")
    void familyAccessShouldDelegateToElderService() {
        given(elderService.requireAccessible(ELDER_ID)).willReturn(elder());
        given(taskMapper.selectList(any())).willReturn(List.of());

        service.todayTasks(ELDER_ID);

        verify(elderService).requireAccessible(ELDER_ID);
        verify(orderMapper, never()).selectCount(any());
    }

    @Test
    @DisplayName("归属 · 陪诊员走「是否有在途订单」，无在途单 → 2006，且不查老人档案")
    void companionAccessShouldRequireOngoingOrder() {
        loginAs(RoleConstants.COMPANION, COMPANION_ID);
        given(orderMapper.selectCount(any())).willReturn(0L);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.todayTasks(ELDER_ID));

        assertEquals(ResultCode.NO_PERMISSION_FOR_ELDER.getCode(), ex.getCode());
        verify(elderService, never()).requireAccessible(any());
        verify(elderMapper, never()).selectById(any());
    }

    /* ================================================================== */

    private MedicationTask task(String status, Long elderId) {
        MedicationTask task = new MedicationTask();
        task.setId(TASK_ID);
        task.setElderId(elderId);
        task.setPlanId(10001L);
        task.setMedicineName("苯磺酸氨氯地平片");
        task.setDosage("1 片");
        task.setStatus(status);
        task.setPlanTime(LocalDateTime.now().minusHours(3));
        return task;
    }

    private ElderProfile elder() {
        ElderProfile elder = new ElderProfile();
        elder.setId(ELDER_ID);
        elder.setUserId(201L);
        elder.setName("张德海");
        return elder;
    }

    private MedicineDict medicine() {
        MedicineDict medicine = new MedicineDict();
        medicine.setId(MEDICINE_ID);
        medicine.setName("苯磺酸氨氯地平片");
        return medicine;
    }

    private SysUser user(Long id, String realName) {
        SysUser user = new SysUser();
        user.setId(id);
        user.setRealName(realName);
        return user;
    }

    private MedicationPlanCreateDTO createPlanDto(List<String> timePoints, int frequency) {
        MedicationPlanCreateDTO dto = new MedicationPlanCreateDTO();
        dto.setElderId(ELDER_ID);
        dto.setMedicineId(MEDICINE_ID);
        dto.setDosage("1 片");
        dto.setFrequency(frequency);
        dto.setTimePoints(timePoints);
        dto.setStartDate("2026-09-16");
        dto.setMealRelation("AFTER_MEAL");
        return dto;
    }

    private void loginAs(String role, Long userId) {
        LoginUser loginUser = new LoginUser(userId, "tester", role, 0, "jti",
                System.currentTimeMillis() + 60000);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, loginUser.authorities()));
    }

    /**
     * 抓取传给 {@code update(null, wrapper)} 的更新包装器。
     *
     * <p>必须按 {@link LambdaUpdateWrapper} 类型抓：{@code getSqlSet()} 是
     * {@code Update} 接口上的方法，按父接口 {@code Wrapper} 抓会取不到。</p>
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private LambdaUpdateWrapper<MedicationTask> captureTaskUpdate() {
        ArgumentCaptor<LambdaUpdateWrapper> captor = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(taskMapper).update(any(), captor.capture());
        return captor.getValue();
    }

    /** 同上，但允许在此之前已有其它 update 调用（扫描场景一条任务会写两次） */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private LambdaUpdateWrapper<MedicationTask> captureTaskUpdate(Long ignored) {
        ArgumentCaptor<LambdaUpdateWrapper> captor = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(taskMapper, org.mockito.Mockito.atLeastOnce()).update(any(), captor.capture());
        return captor.getAllValues().get(captor.getAllValues().size() - 1);
    }
}

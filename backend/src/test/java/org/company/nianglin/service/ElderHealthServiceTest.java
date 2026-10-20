package org.company.nianglin.service;

import org.company.nianglin.common.ResultCode;
import org.company.nianglin.constant.HealthTimelineCategory;
import org.company.nianglin.constant.RoleConstants;
import org.company.nianglin.dto.DateRangeQuery;
import org.company.nianglin.dto.HealthTimelineQuery;
import org.company.nianglin.entity.CompanionOrder;
import org.company.nianglin.entity.ElderProfile;
import org.company.nianglin.entity.InternalMessage;
import org.company.nianglin.entity.MedicationTask;
import org.company.nianglin.entity.OrderCheckin;
import org.company.nianglin.exception.BusinessException;
import org.company.nianglin.mapper.CompanionOrderMapper;
import org.company.nianglin.mapper.ElderHealthReadMapper;
import org.company.nianglin.mapper.InternalMessageMapper;
import org.company.nianglin.mapper.MedicationTaskMapper;
import org.company.nianglin.mapper.OrderCheckinMapper;
import org.company.nianglin.mapper.OrderReviewMapper;
import org.company.nianglin.mapper.SysUserMapper;
import org.company.nianglin.security.LoginUser;
import org.company.nianglin.service.impl.ElderHealthServiceImpl;
import org.company.nianglin.support.MybatisLambdaCache;
import org.company.nianglin.vo.ElderHealthOverviewVO;
import org.company.nianglin.vo.FamilyHealthSummaryVO;
import org.company.nianglin.vo.HealthTimelineVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 老人健康协同中心的 Service 单测（V3.2 · FR-Overview / FR-Timeline / FR-Summary）。
 *
 * <p>本组锁住的是<b>Service 自己的判断</b>：排序、分页、分类过滤、空态、
 * 完成率折算、待处理事项的派生。
 * SQL 那一层由 {@code ElderHealthReadMapperTest} 在真库上单独验。</p>
 *
 * @author 银龄伴诊团队
 * @since V3.2
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("老人健康协同：概览口径 / 时间线排序分页 / 家庭摘要")
class ElderHealthServiceTest {

    private static final Long ELDER_ID = 301L;
    private static final Long FAMILY_ID = 101L;

    @Mock
    private ElderHealthReadMapper healthReadMapper;
    @Mock
    private ElderService elderService;
    @Mock
    private SysUserMapper sysUserMapper;
    @Mock
    private CompanionOrderMapper companionOrderMapper;
    @Mock
    private OrderCheckinMapper orderCheckinMapper;
    @Mock
    private MedicationTaskMapper medicationTaskMapper;
    @Mock
    private InternalMessageMapper internalMessageMapper;
    @Mock
    private OrderReviewMapper orderReviewMapper;

    private ElderHealthService service;

    @BeforeEach
    void setUp() {
        MybatisLambdaCache.warmUp();
        service = new ElderHealthServiceImpl(healthReadMapper, elderService, sysUserMapper,
                companionOrderMapper, orderCheckinMapper, medicationTaskMapper,
                internalMessageMapper, orderReviewMapper);
        loginAsFamily();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    /* ================================================================== */
    /* 概览                                                                */
    /* ================================================================== */

    @Nested
    @DisplayName("健康概览")
    class Overview {

        @Test
        @DisplayName("完成率 = 已服 / 总数")
        void completionRateShouldBeTakenOverTotal() {
            stubEverythingElseEmpty();
            given(elderService.requireAccessible(ELDER_ID)).willReturn(elder());
            given(healthReadMapper.selectMedicationBucketsByElder(anyCollection(), any(), any()))
                    .willReturn(List.of(row("total", 30, "taken", 27, "missed", 2, "pending", 1)));

            ElderHealthOverviewVO overview = service.overview(ELDER_ID, new DateRangeQuery());

            assertEquals(30, overview.getMedication().getTotal());
            assertEquals(27, overview.getMedication().getTaken());
            assertEquals(2, overview.getMedication().getMissed());
            assertEquals(1, overview.getMedication().getPending());
            assertEquals("90.00%", overview.getMedication().getCompletionRate());
        }

        @Test
        @DisplayName("区间内一条任务都没有 → 完成率 0.00%，不是 NaN%")
        void completionRateShouldBeZeroWhenNoTask() {
            stubEverythingElseEmpty();
            given(elderService.requireAccessible(ELDER_ID)).willReturn(elder());
            given(healthReadMapper.selectMedicationBucketsByElder(anyCollection(), any(), any()))
                    .willReturn(List.of());

            ElderHealthOverviewVO overview = service.overview(ELDER_ID, new DateRangeQuery());

            assertEquals(0, overview.getMedication().getTotal());
            assertEquals("0.00%", overview.getMedication().getCompletionRate());
        }

        @Test
        @DisplayName("越权访问他人老人 → 归属校验的 2006 直接抛出，Service 不吞")
        void accessErrorShouldPropagate() {
            given(elderService.requireAccessible(ELDER_ID))
                    .willThrow(new BusinessException(ResultCode.NO_PERMISSION_FOR_ELDER));

            BusinessException e = assertThrows(BusinessException.class,
                    () -> service.overview(ELDER_ID, new DateRangeQuery()));
            assertEquals(ResultCode.NO_PERMISSION_FOR_ELDER.getCode(), e.getCode());
        }

        @Test
        @DisplayName("「今日用药」是当天口径，不是统计区间口径")
        void todayMedicationShouldNotUseRangeCaliber() {
            stubEverythingElseEmpty();
            given(elderService.requireAccessible(ELDER_ID)).willReturn(elder());
            // 区间口径：30 天里已服 27 次
            given(healthReadMapper.selectMedicationBucketsByElder(anyCollection(), any(), any()))
                    .willReturn(List.of(row("total", 30, "taken", 27, "missed", 2, "pending", 1)));
            // 今日口径：今天已服 2 次
            given(healthReadMapper.selectTodayMedicationByElder(anyCollection(), any()))
                    .willReturn(List.of(row("total", 3, "taken", 2, "missed", 0, "pending", 1)));

            ElderHealthOverviewVO overview = service.overview(ELDER_ID, new DateRangeQuery());

            assertEquals(27, overview.getMedication().getTaken(), "区间口径的已服数");
            assertEquals(2, overview.getTodayMedication().getTaken(),
                    "「今日已服药」必须用当天口径，不能拿区间口径冒充");
            assertEquals(3, overview.getTodayMedication().getTotal());
            assertEquals(1, overview.getTodayMedication().getPending());
        }
    }

    /* ================================================================== */
    /* 时间线                                                              */
    /* ================================================================== */

    @Nested
    @DisplayName("健康时间线")
    class Timeline {

        @Test
        @DisplayName("四类事件合并后按发生时间倒序")
        void eventsShouldBeSortedByTimeDesc() {
            given(elderService.requireAccessible(ELDER_ID)).willReturn(elder());

            CompanionOrder order = new CompanionOrder();
            order.setId(1001L);
            order.setCreateTime(LocalDateTime.now().minusDays(3));
            order.setAcceptTime(LocalDateTime.now().minusDays(2));
            given(companionOrderMapper.selectList(any())).willReturn(List.of(order));

            OrderCheckin checkin = new OrderCheckin();
            checkin.setId(9001L);
            checkin.setOrderId(1001L);
            checkin.setNode("ARRIVE");
            checkin.setCheckinTime(LocalDateTime.now().minusDays(1));
            given(orderCheckinMapper.selectList(any())).willReturn(List.of(checkin));

            MedicationTask task = new MedicationTask();
            task.setId(60001L);
            task.setStatus("TAKEN");
            task.setPlanTime(LocalDateTime.now().minusHours(5));
            task.setConfirmTime(LocalDateTime.now().minusHours(4));
            given(medicationTaskMapper.selectList(any())).willReturn(List.of(task));

            InternalMessage message = new InternalMessage();
            message.setId(7001L);
            message.setType("ORDER_PROGRESS");
            message.setBizType("ORDER");
            message.setBizId(1001L);
            message.setTitle("陪诊进度更新");
            message.setCreateTime(LocalDateTime.now().minusHours(2));
            given(internalMessageMapper.selectList(any())).willReturn(List.of(message));

            HealthTimelineVO timeline = service.timeline(ELDER_ID, new HealthTimelineQuery());

            List<HealthTimelineVO.Item> items = timeline.getItems();
            assertEquals(5, items.size());
            for (int i = 1; i < items.size(); i++) {
                assertFalse(items.get(i).getOccurredAt().isAfter(items.get(i - 1).getOccurredAt()),
                        "事件必须按发生时间倒序排列");
            }
            // 消息（2 小时前）→ 用药（4 小时前）→ 打卡（1 天前）→ 接单（2 天前）→ 下单（3 天前）
            assertEquals(HealthTimelineCategory.MESSAGE.name(), items.get(0).getCategory());
            assertEquals(HealthTimelineCategory.MEDICATION.name(), items.get(1).getCategory());
            assertEquals(HealthTimelineCategory.CHECKIN.name(), items.get(2).getCategory());
            assertEquals(HealthTimelineCategory.ORDER.name(), items.get(3).getCategory());
        }

        @Test
        @DisplayName("limit 截断 + hasMore + nextCursor，翻页不重复")
        void timelineShouldPaginateWithCursor() {
            stubEverythingElseEmpty();
            given(elderService.requireAccessible(ELDER_ID)).willReturn(elder());

            List<CompanionOrder> orders = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                CompanionOrder order = new CompanionOrder();
                order.setId(1000L + i);
                order.setCreateTime(LocalDateTime.now().minusDays(i + 1));
                orders.add(order);
            }
            given(companionOrderMapper.selectList(any())).willReturn(orders);

            HealthTimelineQuery query = new HealthTimelineQuery();
            query.setLimit(2);
            HealthTimelineVO first = service.timeline(ELDER_ID, query);

            assertEquals(2, first.getItems().size());
            assertTrue(first.getHasMore());
            assertNotNull(first.getNextCursor());

            query.setCursor(first.getNextCursor());
            HealthTimelineVO second = service.timeline(ELDER_ID, query);
            assertEquals(2, second.getItems().size());
            assertFalse(first.getItems().get(0).getEventId().equals(second.getItems().get(0).getEventId()),
                    "第二页不得重复第一页的条目");
        }

        @Test
        @DisplayName("没有数据时返回空数组，不返回 null、不报错")
        void emptyDataShouldReturnEmptyArray() {
            given(elderService.requireAccessible(ELDER_ID)).willReturn(elder());
            stubEverythingElseEmpty();

            HealthTimelineVO timeline = service.timeline(ELDER_ID, new HealthTimelineQuery());

            assertNotNull(timeline.getItems());
            assertTrue(timeline.getItems().isEmpty());
            assertFalse(timeline.getHasMore());
            assertNull(timeline.getNextCursor());
        }

        @Test
        @DisplayName("categories 过滤：只保留选中的分类")
        void categoriesShouldFilterEvents() {
            stubEverythingElseEmpty();
            given(elderService.requireAccessible(ELDER_ID)).willReturn(elder());

            MedicationTask task = new MedicationTask();
            task.setId(60001L);
            task.setStatus("TAKEN");
            task.setPlanTime(LocalDateTime.now().minusDays(1));
            task.setConfirmTime(LocalDateTime.now().minusDays(1));
            // 这条用药事件本不该被查询到；用 lenient 是因为「没被调用」正是本用例的断言对象
            lenient().when(medicationTaskMapper.selectList(any())).thenReturn(List.of(task));

            HealthTimelineQuery query = new HealthTimelineQuery();
            query.setCategories("ORDER");
            HealthTimelineVO timeline = service.timeline(ELDER_ID, query);

            assertTrue(timeline.getItems().isEmpty(), "只选 ORDER 时不应出现用药事件");
            verify(medicationTaskMapper, never()).selectList(any());
        }

        @ParameterizedTest(name = "非法 categories={0} → 400")
        @ValueSource(strings = {"NOT_A_CATEGORY", "ORDER,XXX"})
        @DisplayName("非法分类取值 → 400（不静默降级）")
        void illegalCategoriesShouldBeRejected(String categories) {
            given(elderService.requireAccessible(ELDER_ID)).willReturn(elder());

            HealthTimelineQuery query = new HealthTimelineQuery();
            query.setCategories(categories);

            BusinessException e = assertThrows(BusinessException.class,
                    () -> service.timeline(ELDER_ID, query));
            assertEquals(ResultCode.PARAM_ERROR.getCode(), e.getCode());
        }

        @Test
        @DisplayName("非法游标 → 400")
        void illegalCursorShouldBeRejected() {
            given(elderService.requireAccessible(ELDER_ID)).willReturn(elder());
            stubEverythingElseEmpty();

            HealthTimelineQuery query = new HealthTimelineQuery();
            query.setCursor("!!!not-base64!!!");

            BusinessException e = assertThrows(BusinessException.class,
                    () -> service.timeline(ELDER_ID, query));
            assertEquals(ResultCode.PARAM_ERROR.getCode(), e.getCode());
        }

        @Test
        @DisplayName("事件触到取数上限 → truncated = true（不许让调用方以为看到的就是全部）")
        void truncatedShouldBeTrueWhenCapReached() {
            stubEverythingElseEmpty();
            given(elderService.requireAccessible(ELDER_ID)).willReturn(elder());

            List<MedicationTask> tasks = new ArrayList<>();
            for (int i = 0; i < 200; i++) {
                MedicationTask task = new MedicationTask();
                task.setId(60000L + i);
                task.setStatus("TAKEN");
                task.setPlanTime(LocalDateTime.now().minusMinutes(i));
                task.setConfirmTime(LocalDateTime.now().minusMinutes(i));
                tasks.add(task);
            }
            given(medicationTaskMapper.selectList(any())).willReturn(tasks);

            HealthTimelineVO timeline = service.timeline(ELDER_ID, new HealthTimelineQuery());

            assertTrue(timeline.getTruncated(), "触到取数上限必须如实告知调用方");
        }

        @Test
        @DisplayName("事件没触到上限 → truncated = false")
        void truncatedShouldBeFalseWhenBelowCap() {
            stubEverythingElseEmpty();
            given(elderService.requireAccessible(ELDER_ID)).willReturn(elder());

            HealthTimelineVO timeline = service.timeline(ELDER_ID, new HealthTimelineQuery());

            assertFalse(timeline.getTruncated(), "没触顶就不该报截断");
        }
    }

    /* ================================================================== */
    /* 家庭摘要                                                            */
    /* ================================================================== */

    @Nested
    @DisplayName("家庭健康协同首页摘要")
    class Summary {

        @Test
        @DisplayName("没有绑定老人 → 空列表（不报错、不返回 null）")
        void noElderShouldReturnEmptyList() {
            given(healthReadMapper.selectFamilyElders(FAMILY_ID)).willReturn(List.of());

            assertTrue(service.familySummary().isEmpty());
            verify(healthReadMapper, never()).selectMedicationBucketsByElder(anyCollection(), any(), any());
        }

        @Test
        @DisplayName("多位老人各自一栏，姓名脱敏，待处理条数与概览同源")
        void eachElderShouldBeSummarizedSeparately() {
            given(healthReadMapper.selectFamilyElders(FAMILY_ID)).willReturn(List.of(
                    row("elderId", 301L, "name", "张三丰", "gender", "MALE", "relation", "SON"),
                    row("elderId", 302L, "name", "李四", "gender", "FEMALE", "relation", "DAUGHTER")));
            given(healthReadMapper.selectTodayMedicationByElder(anyCollection(), any())).willReturn(List.of(
                    row("elderId", 301L, "total", 3, "taken", 2, "pending", 1),
                    row("elderId", 302L, "total", 1, "taken", 1, "pending", 0)));
            given(healthReadMapper.selectVisitStatsByElder(anyCollection(), any(), any())).willReturn(List.of(
                    row("elderId", 301L, "orderCount", 1, "companionCount", 1),
                    row("elderId", 302L, "orderCount", 0, "companionCount", 0)));
            given(healthReadMapper.selectPendingCountsByElder(anyCollection())).willReturn(List.of(
                    row("elderId", 301L, "waitingOrders", 1, "toReviewOrders", 0, "riskOrders", 0),
                    row("elderId", 302L, "waitingOrders", 0, "toReviewOrders", 0, "riskOrders", 0)));

            List<FamilyHealthSummaryVO> summaries = service.familySummary();

            assertEquals(2, summaries.size());
            FamilyHealthSummaryVO first = summaries.get(0);
            assertEquals(301L, first.getElderId());
            assertEquals("张*丰", first.getElderName());
            assertEquals("儿子", first.getRelationLabel());
            assertEquals(3, first.getTodayMedication().getTotal());
            assertEquals(2, first.getTodayMedication().getTaken());
            // 待处理：今日待服 1 类 + 订单等待接单 1 类 = 2 类
            assertEquals(2, first.getPendingItemCount());
        }

        @Test
        @DisplayName("只统计当前家属名下的老人")
        void onlyCurrentFamilyEldersShouldBeIncluded() {
            given(healthReadMapper.selectFamilyElders(FAMILY_ID)).willReturn(List.of(
                    row("elderId", 301L, "name", "张三丰", "relation", "SON")));
            given(healthReadMapper.selectTodayMedicationByElder(anyCollection(), any())).willReturn(List.of());
            given(healthReadMapper.selectVisitStatsByElder(anyCollection(), any(), any())).willReturn(List.of());
            given(healthReadMapper.selectPendingCountsByElder(anyCollection())).willReturn(List.of());

            service.familySummary();

            verify(healthReadMapper).selectFamilyElders(FAMILY_ID);
        }
    }

    /* ================================================================== */
    /* 工具                                                                */
    /* ================================================================== */

    /** 造一行结果（允许 null 值 —— {@code Map.of} 不接受 null） */
    private static Map<String, Object> row(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            map.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        }
        return map;
    }

    /**
     * 把与本用例无关的取数一律打桩成空。
     *
     * <p>用 {@code lenient} 是因为不同用例会走不同的分支
     * （比如订单为空时就不会去查打卡），严格桩会把「没走到」当成错误。</p>
     */
    private void stubEverythingElseEmpty() {
        lenient().when(healthReadMapper.selectMedicationBucketsByElder(anyCollection(), any(), any()))
                .thenReturn(List.of());
        lenient().when(healthReadMapper.selectVisitStatsByElder(anyCollection(), any(), any()))
                .thenReturn(List.of());
        lenient().when(healthReadMapper.selectPendingCountsByElder(anyCollection()))
                .thenReturn(List.of());
        lenient().when(healthReadMapper.selectTodayMedicationByElder(anyCollection(), any()))
                .thenReturn(List.of());
        lenient().when(companionOrderMapper.selectList(any())).thenReturn(List.of());
        lenient().when(orderCheckinMapper.selectList(any())).thenReturn(List.of());
        lenient().when(medicationTaskMapper.selectList(any())).thenReturn(List.of());
        lenient().when(internalMessageMapper.selectList(any())).thenReturn(List.of());
        lenient().when(orderReviewMapper.selectList(any())).thenReturn(List.of());
    }

    private ElderProfile elder() {
        ElderProfile elder = new ElderProfile();
        elder.setId(ELDER_ID);
        elder.setName("张三丰");
        elder.setGender("MALE");
        elder.setBirthDate(LocalDate.now().minusYears(82));
        return elder;
    }

    private void loginAsFamily() {
        LoginUser loginUser = new LoginUser(FAMILY_ID, "family101", RoleConstants.FAMILY, 0,
                "jti-test", System.currentTimeMillis() + 60000);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, loginUser.authorities()));
    }
}

package org.company.nianglin.service;

import org.company.nianglin.config.OperationsProperties;
import org.company.nianglin.dto.DateRangeQuery;
import org.company.nianglin.dto.OperationsTrendQuery;
import org.company.nianglin.exception.BusinessException;
import org.company.nianglin.mapper.OperationsMapper;
import org.company.nianglin.service.impl.OperationsServiceImpl;
import org.company.nianglin.service.support.metrics.MetricCalculatorRegistry;
import org.company.nianglin.vo.FulfillmentSummaryVO;
import org.company.nianglin.vo.OperationsExceptionItemVO;
import org.company.nianglin.vo.OperationsExceptionVO;
import org.company.nianglin.vo.OperationsHealthVO;
import org.company.nianglin.vo.OperationsOverviewVO;
import org.company.nianglin.vo.OperationsTrendVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 平台运营驾驶舱的口径单测（V3.5 · T6）。
 *
 * <h3>这个测试在防什么</h3>
 *
 * <p>统计口径最常见的失守方式不是「算错」，而是「算的东西变了但没人发现」：
 * 分母悄悄加了一项、样本门槛悄悄降了、两个名字悄悄变成了同一个数。
 * 所以每个指标都锁死<b>一条正向 + 一条反向</b>。</p>
 *
 * <h3>「反向」长什么样</h3>
 *
 * <p>不是「把代码改坏再断言变红」那种手工流程，而是<b>把正确的那个数写死在断言里</b>：
 * 比如履约率断言成 {@code "96.15%"}（= 100 / 104）而不是 {@code "95.24%"}
 * （= 100 / 105 的另一种凑法）—— 分母一旦多算一项，这个断言立刻对不上。</p>
 *
 * @author 银龄伴诊团队
 * @since V3.5
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("V3.5 运营驾驶舱：每条口径一条正向 + 一条反向")
class OperationsServiceTest {

    @Mock
    private OperationsMapper mapper;

    @Mock
    private AdminService adminService;

    /** 指标计算器注册表（V3.6 · 效果对比用） */
    @Mock
    private MetricCalculatorRegistry metricCalculatorRegistry;

    /** 真实配置对象（默认值即工单里的默认值），便于按用例调阈值 */
    private final OperationsProperties properties = new OperationsProperties();

    private OperationsServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new OperationsServiceImpl(mapper, adminService, properties, metricCalculatorRegistry);
    }

    /* ================================================================== */
    /* 1 · 概览：履约率 / 投诉率 / 平均评分                                  */
    /* ================================================================== */

    @Test
    @DisplayName("概览 · 履约率 = 完成 ÷（完成 + 取消），取消单只进分母")
    void overviewFulfillmentRateShouldUseCompletedPlusCancelled() {
        stubOrderStats(120, 100, 4, 9, 6, 12, 88, 6L);
        stubComplaintStats(3, 4, 2);
        stubReviewStats("4.50", 20);

        OperationsOverviewVO vo = service.overview(new DateRangeQuery());

        // 100 / (100 + 4) = 96.15%
        assertEquals("96.15%", vo.getFulfillmentRate());
        assertEquals(100, vo.getCompletedOrderCount());
        assertEquals(9, vo.getInServiceCount());
        assertEquals(6, vo.getPendingCount());
        assertEquals(12, vo.getTodayOrderCount());
        assertEquals(6, vo.getAvgAcceptMinutes());
    }

    @Test
    @DisplayName("概览 · 履约率反向：完成与取消各一半时是 50.00%（不是 100%）")
    void overviewFulfillmentRateShouldNotBeFullWhenHalfCancelled() {
        stubOrderStats(20, 10, 10, 0, 0, 0, 5, 3L);
        stubComplaintStats(0, 0, 0);
        stubReviewStats(null, 0);

        OperationsOverviewVO vo = service.overview(new DateRangeQuery());

        assertEquals("50.00%", vo.getFulfillmentRate());
    }

    @Test
    @DisplayName("概览 · 投诉率的分子是「投诉订单数」而不是「投诉记录数」")
    void overviewComplaintRateShouldCountDistinctComplaintOrders() {
        stubOrderStats(120, 100, 4, 9, 6, 12, 88, 6L);
        // 9 条投诉记录落在 3 笔订单上：同一单多次投诉只算一单
        stubComplaintStats(3, 9, 2);
        stubReviewStats("4.50", 20);

        OperationsOverviewVO vo = service.overview(new DateRangeQuery());

        // 3 / 120 = 2.50%（若误用记录数会得到 7.50%）
        assertEquals("2.50%", vo.getComplaintRate());
    }

    @Test
    @DisplayName("概览 · 平均评分是两位小数字符串")
    void overviewAverageScoreShouldBeTwoDecimalString() {
        stubOrderStats(120, 100, 4, 9, 6, 12, 88, 6L);
        stubComplaintStats(3, 4, 2);
        stubReviewStats("4.5", 20);

        OperationsOverviewVO vo = service.overview(new DateRangeQuery());

        assertEquals("4.50", vo.getAverageScore());
    }

    @Test
    @DisplayName("概览 · 空数据返回 0 与 0.00%，不返回 null、不抛异常")
    void overviewShouldReturnZerosOnEmptyData() {
        when(mapper.selectOrderStats(any(), any(), anyInt(), any(), any())).thenReturn(new HashMap<>());
        when(mapper.selectComplaintStats(any(), any())).thenReturn(new HashMap<>());
        when(mapper.selectReviewStats(any(), any())).thenReturn(new HashMap<>());

        OperationsOverviewVO vo = service.overview(new DateRangeQuery());

        assertNotNull(vo);
        assertEquals(0, vo.getTodayOrderCount());
        assertEquals(0, vo.getCompletedOrderCount());
        assertEquals(0, vo.getInServiceCount());
        assertEquals(0, vo.getPendingCount());
        assertEquals(0, vo.getAvgAcceptMinutes());
        assertEquals("0.00%", vo.getFulfillmentRate());
        assertEquals("0.00%", vo.getComplaintRate());
        assertEquals("0.00", vo.getAverageScore());
        assertNotNull(vo.getPeriodStart());
        assertNotNull(vo.getPeriodEnd());
    }

    /* ================================================================== */
    /* 2 · 健康度：六个并列指标，且履约率 ≠ 完成率                          */
    /* ================================================================== */

    @Test
    @DisplayName("健康度 · 完成率（按时）= 按时完成 ÷ 已完成，与履约率不是一个数")
    void healthCompletionRateShouldDifferFromFulfillmentRate() {
        stubOrderStats(120, 100, 4, 9, 6, 12, 88, 6L);
        when(mapper.selectCompanionStats(any(), any())).thenReturn(Map.of("activeCompanionCount", 24L));
        stubComplaintStats(3, 4, 2);
        stubReviewStats("4.50", 20);

        OperationsHealthVO vo = service.health(new DateRangeQuery());

        // 履约率 = 100 / 104 = 96.15%；完成率（按时）= 88 / 100 = 88.00%
        assertEquals("96.15%", vo.getFulfillmentRate());
        assertEquals("88.00%", vo.getCompletionRate());
        assertNotEquals(vo.getFulfillmentRate(), vo.getCompletionRate(),
                "履约率与完成率（按时）必须是两个不同的数 —— 两个名字一个数是禁止的");
    }

    @Test
    @DisplayName("健康度 · 六个指标齐全（少一个就会红），且不给合成分")
    void healthShouldExposeExactlySixIndicators() {
        stubOrderStats(120, 100, 4, 9, 6, 12, 88, 6L);
        when(mapper.selectCompanionStats(any(), any())).thenReturn(Map.of("activeCompanionCount", 24L));
        stubComplaintStats(3, 4, 2);
        stubReviewStats("4.50", 20);

        OperationsHealthVO vo = service.health(new DateRangeQuery());

        assertNotNull(vo.getFulfillmentRate());
        assertNotNull(vo.getAvgAcceptMinutes());
        assertNotNull(vo.getCompletionRate());
        assertNotNull(vo.getComplaintRate());
        assertNotNull(vo.getAverageScore());
        assertNotNull(vo.getActiveCompanionCount());
        assertEquals("6", vo.getAvgAcceptMinutes());
        assertEquals(24, vo.getActiveCompanionCount());
        // 六项都是字符串 / 计数，没有任何一个叫「健康指数」「综合评分」的合成分
        assertEquals(6, java.util.Arrays.stream(OperationsHealthVO.class.getDeclaredFields())
                .filter(f -> !f.isSynthetic() && !java.lang.reflect.Modifier.isStatic(f.getModifiers()))
                .count());
    }

    @Test
    @DisplayName("健康度 · 空数据返回 0.00% / 0，不返回 null")
    void healthShouldReturnZerosOnEmptyData() {
        when(mapper.selectOrderStats(any(), any(), anyInt(), any(), any())).thenReturn(new HashMap<>());
        when(mapper.selectCompanionStats(any(), any())).thenReturn(new HashMap<>());
        when(mapper.selectComplaintStats(any(), any())).thenReturn(new HashMap<>());
        when(mapper.selectReviewStats(any(), any())).thenReturn(new HashMap<>());

        OperationsHealthVO vo = service.health(new DateRangeQuery());

        assertEquals("0.00%", vo.getFulfillmentRate());
        assertEquals("0.00%", vo.getCompletionRate());
        assertEquals("0.00%", vo.getComplaintRate());
        assertEquals("0.00", vo.getAverageScore());
        assertEquals("0", vo.getAvgAcceptMinutes());
        assertEquals(0, vo.getActiveCompanionCount());
    }

    /* ================================================================== */
    /* 3 · 趋势：分桶补 0 + 长度一致 + 非法维度 / 区间                      */
    /* ================================================================== */

    @Test
    @DisplayName("趋势 · 无数据的桶补 0，categories 与 series.data 长度一致")
    void trendShouldFillEmptyBucketsWithZero() {
        when(mapper.selectDailyOrderTrend(any(), any())).thenReturn(List.of(
                Map.of("d", "2026-10-01", "totalCount", 3L, "completedCount", 2L,
                        "cancelledCount", 1L, "activeCompanionCount", 2L),
                Map.of("d", "2026-10-03", "totalCount", 5L, "completedCount", 5L,
                        "cancelledCount", 0L, "activeCompanionCount", 4L)));

        OperationsTrendVO vo = service.trend(customTrend("ORDER", "2026-10-01", "2026-10-03"));

        assertEquals(3, vo.getCategories().size());
        assertEquals(1, vo.getSeries().size());
        assertEquals(3, vo.getSeries().get(0).getData().size());
        assertEquals(new BigDecimal("3.00"), vo.getSeries().get(0).getData().get(0));
        assertEquals(new BigDecimal("0.00"), vo.getSeries().get(0).getData().get(1));
        assertEquals(new BigDecimal("5.00"), vo.getSeries().get(0).getData().get(2));
        assertEquals("订单量", vo.getSeries().get(0).getName());
        assertEquals("ORDER", vo.getDimension());
        assertEquals("CUSTOM", vo.getRange());
        assertEquals("DAY", vo.getGranularity());
    }

    @Test
    @DisplayName("趋势 · 履约率维度按天算百分比（90.00 而不是 0.9）")
    void trendFulfillmentRateShouldBePercentNumber() {
        when(mapper.selectDailyOrderTrend(any(), any())).thenReturn(List.of(
                Map.of("d", "2026-10-01", "totalCount", 10L, "completedCount", 9L,
                        "cancelledCount", 1L, "activeCompanionCount", 2L)));

        OperationsTrendVO vo = service.trend(customTrend("FULFILLMENT_RATE", "2026-10-01", "2026-10-01"));

        assertEquals(new BigDecimal("90.00"), vo.getSeries().get(0).getData().get(0));
    }

    @Test
    @DisplayName("趋势 · 活跃陪诊员维度取当天去重的接单陪诊员数")
    void trendCompanionActiveShouldUseDistinctCompanionCount() {
        when(mapper.selectDailyOrderTrend(any(), any())).thenReturn(List.of(
                Map.of("d", "2026-10-01", "totalCount", 10L, "completedCount", 9L,
                        "cancelledCount", 1L, "activeCompanionCount", 4L)));

        OperationsTrendVO vo = service.trend(customTrend("COMPANION_ACTIVE", "2026-10-01", "2026-10-01"));

        assertEquals(new BigDecimal("4.00"), vo.getSeries().get(0).getData().get(0));
        assertEquals("活跃陪诊员数", vo.getSeries().get(0).getName());
    }

    @Test
    @DisplayName("趋势 · 投诉维度按天取投诉订单数")
    void trendComplaintShouldUseDailyComplaintOrders() {
        when(mapper.selectDailyComplaintTrend(any(), any())).thenReturn(List.of(
                Map.of("d", "2026-10-01", "cnt", 2L)));

        OperationsTrendVO vo = service.trend(customTrend("COMPLAINT", "2026-10-01", "2026-10-02"));

        assertEquals(new BigDecimal("2.00"), vo.getSeries().get(0).getData().get(0));
        assertEquals(new BigDecimal("0.00"), vo.getSeries().get(0).getData().get(1));
    }

    @Test
    @DisplayName("趋势 · 评价维度按天取有效评价条数")
    void trendReviewShouldUseDailyReviewCount() {
        when(mapper.selectDailyReviewTrend(any(), any())).thenReturn(List.of(
                Map.of("d", "2026-10-01", "cnt", 7L)));

        OperationsTrendVO vo = service.trend(customTrend("REVIEW", "2026-10-01", "2026-10-01"));

        assertEquals(new BigDecimal("7.00"), vo.getSeries().get(0).getData().get(0));
    }

    @Test
    @DisplayName("趋势 · 用药维度按天取服药任务数")
    void trendMedicationShouldUseDailyTaskCount() {
        when(mapper.selectDailyMedicationTrend(any(), any())).thenReturn(List.of(
                Map.of("d", "2026-10-01", "total", 50L, "taken", 46L, "missed", 4L, "pending", 0L)));

        OperationsTrendVO vo = service.trend(customTrend("MEDICATION", "2026-10-01", "2026-10-01"));

        assertEquals(new BigDecimal("50.00"), vo.getSeries().get(0).getData().get(0));
    }

    @Test
    @DisplayName("趋势 · 空数据时返回等长的零序列，而不是空指针")
    void trendShouldReturnAlignedZeroSeriesOnNoData() {
        when(mapper.selectDailyOrderTrend(any(), any())).thenReturn(List.of());

        OperationsTrendVO vo = service.trend(customTrend("ORDER", "2026-10-01", "2026-10-03"));

        assertEquals(3, vo.getCategories().size());
        assertEquals(3, vo.getSeries().get(0).getData().size());
        assertEquals(new BigDecimal("0.00"), vo.getSeries().get(0).getData().get(0));
    }

    @Test
    @DisplayName("趋势 · LAST_7D 固定 7 个桶（起点是今天往前 6 天）")
    void trendLastSevenDaysShouldCoverSevenBuckets() {
        when(mapper.selectDailyOrderTrend(any(), any())).thenReturn(List.of());

        OperationsTrendVO vo = service.trend(trend("ORDER", "LAST_7D"));

        assertEquals(7, vo.getCategories().size());
        assertEquals("LAST_7D", vo.getRange());
    }

    @Test
    @DisplayName("趋势 · 非法维度抛 BusinessException（400），且不查库")
    void trendShouldRejectInvalidDimension() {
        OperationsTrendQuery query = customTrend("WHATEVER", "2026-10-01", "2026-10-03");

        BusinessException ex = assertThrows(BusinessException.class, () -> service.trend(query));

        assertEquals(400, ex.getCode());
        verify(mapper, never()).selectDailyOrderTrend(any(), any());
    }

    @Test
    @DisplayName("趋势 · 非法区间档位抛 BusinessException（400），且不查库")
    void trendShouldRejectInvalidRange() {
        OperationsTrendQuery query = trend("ORDER", "LAST_99Y");

        BusinessException ex = assertThrows(BusinessException.class, () -> service.trend(query));

        assertEquals(400, ex.getCode());
        verify(mapper, never()).selectDailyOrderTrend(any(), any());
    }

    @Test
    @DisplayName("趋势 · 自定义区间超过 366 天抛 9002")
    void trendShouldRejectTooLongCustomRange() {
        OperationsTrendQuery query = customTrend("ORDER", "2024-01-01", "2026-10-20");

        BusinessException ex = assertThrows(BusinessException.class, () -> service.trend(query));

        assertEquals(9002, ex.getCode());
    }

    /* ================================================================== */
    /* 4 · 异常中心：四项固定 + 门槛 + 路由可达                             */
    /* ================================================================== */

    @Test
    @DisplayName("异常中心 · 四项恒定全部返回，total 是四项之和")
    void exceptionsShouldAlwaysReturnFourItems() {
        when(adminService.fulfillmentSummary()).thenReturn(new FulfillmentSummaryVO()
                .setManualRequired(3).setUrgent(1).setWarning(5).setNormal(10));
        when(mapper.selectCompanionComplaintRates(any(), any())).thenReturn(List.of(
                Map.of("companionId", 320L, "complaintCount", 5L, "orderCount", 10L)));
        when(mapper.selectMissedMedicationCount(any(), any())).thenReturn(7);
        when(mapper.selectRecentMissedOrders(any(), any(), anyInt())).thenReturn(List.of(
                Map.of("orderId", 1019L)));

        OperationsExceptionVO vo = service.exceptions(new DateRangeQuery());

        assertEquals(4, vo.getItems().size());
        // 3 + (5 + 1) + 1 + 7 = 17
        assertEquals(17, vo.getTotal());
        assertEquals("MANUAL_REQUIRED", vo.getItems().get(0).getType());
        assertEquals("需人工介入订单", vo.getItems().get(0).getTypeLabel());
        assertEquals(3, vo.getItems().get(0).getCount());
        assertEquals("DANGER", vo.getItems().get(0).getLevel());
        assertEquals(6, vo.getItems().get(1).getCount());
        assertEquals(1, vo.getItems().get(2).getCount());
        assertEquals(7, vo.getItems().get(3).getCount());
    }

    @Test
    @DisplayName("异常中心 · 四张卡片的 linkUrl 都是前端真实存在的路由")
    void exceptionsShouldPointToExistingRoutes() {
        when(adminService.fulfillmentSummary()).thenReturn(new FulfillmentSummaryVO());
        when(mapper.selectCompanionComplaintRates(any(), any())).thenReturn(List.of());
        when(mapper.selectMissedMedicationCount(any(), any())).thenReturn(0);
        when(mapper.selectRecentMissedOrders(any(), any(), anyInt())).thenReturn(List.of());

        OperationsExceptionVO vo = service.exceptions(new DateRangeQuery());

        List<String> allowed = List.of("/admin/fulfillment", "/admin/complaint", "/admin/order");
        for (OperationsExceptionItemVO item : vo.getItems()) {
            assertTrue(allowed.contains(item.getLinkUrl()),
                    "linkUrl 指向了前端不存在的路由：" + item.getLinkUrl());
        }
        assertEquals("/admin/fulfillment", vo.getItems().get(0).getLinkUrl());
        assertEquals("/admin/fulfillment", vo.getItems().get(1).getLinkUrl());
        assertEquals("/admin/complaint", vo.getItems().get(2).getLinkUrl());
        assertEquals("/admin/order", vo.getItems().get(3).getLinkUrl());
    }

    @Test
    @DisplayName("异常中心 · 用药漏服项带最近一条漏服订单的 referenceId，其余为 null")
    void exceptionsShouldCarryReferenceIdForMedicationMissed() {
        when(adminService.fulfillmentSummary()).thenReturn(new FulfillmentSummaryVO());
        when(mapper.selectCompanionComplaintRates(any(), any())).thenReturn(List.of());
        when(mapper.selectMissedMedicationCount(any(), any())).thenReturn(9);
        when(mapper.selectRecentMissedOrders(any(), any(), anyInt())).thenReturn(List.of(
                Map.of("orderId", 1019L, "lastPlanTime", "2026-10-01 08:00:00")));

        OperationsExceptionVO vo = service.exceptions(new DateRangeQuery());

        assertEquals(1019L, vo.getItems().get(3).getReferenceId());
        assertNull(vo.getItems().get(0).getReferenceId());
        assertNull(vo.getItems().get(1).getReferenceId());
        assertNull(vo.getItems().get(2).getReferenceId());
    }

    @Test
    @DisplayName("异常中心 · 投诉率异常陪诊员：接单量低于门槛不判定（样本太小没有意义）")
    void exceptionsShouldIgnoreCompanionsBelowMinOrders() {
        when(adminService.fulfillmentSummary()).thenReturn(new FulfillmentSummaryVO());
        // 接过 2 单被投诉 2 次 = 100%，但接单量 < 3，不该判成异常
        when(mapper.selectCompanionComplaintRates(any(), any())).thenReturn(List.of(
                Map.of("companionId", 320L, "complaintCount", 2L, "orderCount", 2L)));
        when(mapper.selectMissedMedicationCount(any(), any())).thenReturn(0);
        when(mapper.selectRecentMissedOrders(any(), any(), anyInt())).thenReturn(List.of());

        OperationsExceptionVO vo = service.exceptions(new DateRangeQuery());

        assertEquals(0, vo.getItems().get(2).getCount());
    }

    @Test
    @DisplayName("异常中心 · 投诉率异常陪诊员：超过阈值才判定")
    void exceptionsShouldFlagCompanionsAboveThreshold() {
        when(adminService.fulfillmentSummary()).thenReturn(new FulfillmentSummaryVO());
        // 3 单被投诉 1 单 = 33.33% > 20% → 判定为异常
        when(mapper.selectCompanionComplaintRates(any(), any())).thenReturn(List.of(
                Map.of("companionId", 320L, "complaintCount", 1L, "orderCount", 3L)));
        when(mapper.selectMissedMedicationCount(any(), any())).thenReturn(0);
        when(mapper.selectRecentMissedOrders(any(), any(), anyInt())).thenReturn(List.of());

        OperationsExceptionVO vo = service.exceptions(new DateRangeQuery());

        assertEquals(1, vo.getItems().get(2).getCount());
    }

    @Test
    @DisplayName("异常中心 · 用药漏服的说明随阈值变化（0 单时不能说「超过阈值」）")
    void medicationMissedDescriptionShouldFollowThreshold() {
        when(adminService.fulfillmentSummary()).thenReturn(new FulfillmentSummaryVO());
        when(mapper.selectCompanionComplaintRates(any(), any())).thenReturn(List.of());
        when(mapper.selectMissedMedicationCount(any(), any())).thenReturn(2);
        when(mapper.selectRecentMissedOrders(any(), any(), anyInt())).thenReturn(List.of());

        OperationsExceptionVO below = service.exceptions(new DateRangeQuery());
        assertEquals("区间内漏服任务数未超过阈值，最近一条见最近订单", below.getItems().get(3).getDescription());

        when(mapper.selectMissedMedicationCount(any(), any())).thenReturn(5);
        OperationsExceptionVO atThreshold = service.exceptions(new DateRangeQuery());
        assertEquals("区间内漏服任务数超过阈值，最近一条见最近订单", atThreshold.getItems().get(3).getDescription());
    }

    /* ================================================================== */
    /* 工具                                                                */
    /* ================================================================== */

    private void stubOrderStats(long total, long completed, long cancelled, long inService,
                                long pending, long today, long onTime, Long avgAcceptMinutes) {
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalCount", total);
        stats.put("completedCount", completed);
        stats.put("cancelledCount", cancelled);
        stats.put("inServiceCount", inService);
        stats.put("pendingCount", pending);
        stats.put("todayCount", today);
        stats.put("onTimeCompletedCount", onTime);
        stats.put("avgAcceptMinutes", avgAcceptMinutes);
        when(mapper.selectOrderStats(any(), any(), anyInt(), any(), any())).thenReturn(stats);
    }

    private void stubComplaintStats(long complaintOrders, long complaintRecords, long companions) {
        Map<String, Object> stats = new HashMap<>();
        stats.put("complaintOrderCount", complaintOrders);
        stats.put("complaintCount", complaintRecords);
        stats.put("involvedCompanionCount", companions);
        when(mapper.selectComplaintStats(any(), any())).thenReturn(stats);
    }

    private void stubReviewStats(String averageScore, long reviewCount) {
        Map<String, Object> stats = new HashMap<>();
        if (averageScore != null) {
            stats.put("averageScore", new BigDecimal(averageScore));
        }
        stats.put("reviewCount", reviewCount);
        when(mapper.selectReviewStats(any(), any())).thenReturn(stats);
    }

    private static OperationsTrendQuery customTrend(String dimension, String start, String end) {
        OperationsTrendQuery query = trend(dimension, "CUSTOM");
        query.setStartDate(start);
        query.setEndDate(end);
        return query;
    }

    private static OperationsTrendQuery trend(String dimension, String range) {
        OperationsTrendQuery query = new OperationsTrendQuery();
        query.setDimension(dimension);
        query.setRange(range);
        return query;
    }
}

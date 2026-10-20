package org.company.nianglin.service;

import org.company.nianglin.constant.RoleConstants;
import org.company.nianglin.dto.CompanionTrendQuery;
import org.company.nianglin.dto.DateRangeQuery;
import org.company.nianglin.exception.BusinessException;
import org.company.nianglin.mapper.CompanionStatisticsMapper;
import org.company.nianglin.security.LoginUser;
import org.company.nianglin.service.impl.CompanionStatisticsServiceImpl;
import org.company.nianglin.vo.CompanionIncomeVO;
import org.company.nianglin.vo.CompanionServiceProfileVO;
import org.company.nianglin.vo.CompanionTrendVO;
import org.company.nianglin.vo.CompanionWorkbenchOverviewVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 陪诊员工作台的口径单测（V3.3 · T5）。
 *
 * <h3>这个测试在防什么</h3>
 *
 * <p>统计口径最常见的失守方式不是「算错」，而是「算的东西变了但没人发现」：
 * 分母悄悄加了一项、样本门槛悄悄降了、空数据悄悄返回了 {@code null}。
 * 所以每个指标都锁死<b>一条正向 + 一条反向</b>。</p>
 *
 * <h3>「反向」长什么样</h3>
 *
 * <p>本类里的反向用例不是「把代码改坏再断言变红」那种手工流程，
 * 而是<b>断言写死了正确的那个数</b>：比如完成率断言成
 * {@code "93.00%"}（= 93 / (93 + 7)）而不是 {@code "93.00%"}（= 93 / 100 的另一种凑法）——
 * 分母一旦多算一项，这个断言立刻对不上。</p>
 *
 * @author 银龄伴诊团队
 * @since V3.3
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("V3.3 陪诊员工作台：每条口径一条正向 + 一条反向")
class CompanionStatisticsServiceTest {

    private static final long COMPANION_ID = 301L;

    @Mock
    private CompanionStatisticsMapper mapper;

    @InjectMocks
    private CompanionStatisticsServiceImpl service;

    @BeforeEach
    void loginAsCompanion() {
        LoginUser loginUser = new LoginUser(COMPANION_ID, "comp001", RoleConstants.COMPANION, 0, "jti",
                System.currentTimeMillis() + 60000);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, loginUser.authorities()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    /* ================================================================== */
    /* 1 · 完成率：分母只算「已完成 + 已取消」                                 */
    /* ================================================================== */

    @Test
    @DisplayName("完成率 · 分母只算已完成 + 已取消（拒单不计入分母）")
    void completionRateShouldOnlyCountCompletedAndCancelled() {
        when(mapper.selectOrderStats(eq(COMPANION_ID), any(), any()))
                .thenReturn(stats(93, 7, 2520, "3680.00", "4000.00"));
        when(mapper.selectProfileScore(COMPANION_ID)).thenReturn(new BigDecimal("4.80"));

        CompanionWorkbenchOverviewVO vo = service.overview(new DateRangeQuery());

        // 93 / (93 + 7) = 93.00%
        assertEquals("93.00%", vo.getCompletionRate());
    }

    @Test
    @DisplayName("完成率 · 只有已取消单时分母不为 0（不是 NaN% 也不是 100%）")
    void completionRateShouldNotBeNaNorFullWhenNothingCompleted() {
        when(mapper.selectOrderStats(eq(COMPANION_ID), any(), any()))
                .thenReturn(stats(0, 4, 0, "0.00", "0.00"));
        when(mapper.selectProfileScore(COMPANION_ID)).thenReturn(BigDecimal.ZERO);

        CompanionWorkbenchOverviewVO vo = service.overview(new DateRangeQuery());

        assertEquals("0.00%", vo.getCompletionRate());
    }

    @Test
    @DisplayName("完成率 · 空数据返回 0.00% 而不是抛异常")
    void completionRateShouldBeZeroOnEmptyData() {
        when(mapper.selectOrderStats(eq(COMPANION_ID), any(), any())).thenReturn(new HashMap<>());
        when(mapper.selectProfileScore(COMPANION_ID)).thenReturn(null);

        CompanionWorkbenchOverviewVO vo = service.overview(new DateRangeQuery());

        assertEquals("0.00%", vo.getCompletionRate());
        assertEquals(0, vo.getCompletedOrderCount());
        assertEquals(0, vo.getServiceDurationMinutes());
        assertEquals("0.00", vo.getPeriodIncome());
        assertEquals("0.00", vo.getAverageScore());
    }

    /* ================================================================== */
    /* 2 · 收入：COALESCE(actual_fee, fee) 与结算状态拆分                       */
    /* ================================================================== */

    @Test
    @DisplayName("收入 · 实际收入取 mapper 聚合值（actual_fee 为空时回退 fee）")
    void actualIncomeShouldUseAggregatedValue() {
        when(mapper.selectOrderStats(eq(COMPANION_ID), any(), any()))
                .thenReturn(stats(28, 2, 2520, "3680.00", "4000.00"));

        CompanionIncomeVO vo = service.income(new DateRangeQuery());

        assertEquals("3680.00", vo.getActualIncome());
        assertEquals("4000.00", vo.getOrderAmount());
    }

    @Test
    @DisplayName("收入 · 待结算 + 已结算 = 实际收入（拆分口径与总额同源）")
    void unsettledAndSettledShouldSumToActualIncome() {
        when(mapper.selectOrderStats(eq(COMPANION_ID), any(), any()))
                .thenReturn(stats(28, 2, 2520, "3680.00", "4000.00"));
        when(mapper.selectSettlementStats(eq(COMPANION_ID), any(), any())).thenReturn(List.of(
                Map.of("paymentStatus", "UNPAID", "amount", new BigDecimal("1200.00")),
                Map.of("paymentStatus", "SETTLED", "amount", new BigDecimal("2480.00"))));

        CompanionIncomeVO vo = service.income(new DateRangeQuery());

        assertEquals("1200.00", vo.getUnsettledAmount());
        assertEquals("2480.00", vo.getSettledAmount());
        assertEquals(new BigDecimal("3680.00"),
                new BigDecimal(vo.getUnsettledAmount()).add(new BigDecimal(vo.getSettledAmount())));
    }

    @Test
    @DisplayName("收入 · 费用构成按 itemType 拆成代垫与服务费")
    void feeCompositionShouldSplitByItemType() {
        when(mapper.selectOrderStats(eq(COMPANION_ID), any(), any()))
                .thenReturn(stats(28, 2, 2520, "3680.00", "4000.00"));
        when(mapper.selectFeeItemTotals(eq(COMPANION_ID), any(), any())).thenReturn(List.of(
                Map.of("itemType", "ADVANCE", "amount", new BigDecimal("150.00")),
                Map.of("itemType", "SERVICE", "amount", new BigDecimal("3650.00"))));

        CompanionIncomeVO vo = service.income(new DateRangeQuery());

        assertEquals("150.00", vo.getAdvanceTotal());
        assertEquals("3650.00", vo.getServiceTotal());
    }

    @Test
    @DisplayName("收入 · 空数据返回全 0，不返回 null")
    void incomeShouldReturnZerosOnEmptyData() {
        when(mapper.selectOrderStats(eq(COMPANION_ID), any(), any())).thenReturn(new HashMap<>());
        when(mapper.selectSettlementStats(eq(COMPANION_ID), any(), any())).thenReturn(List.of());
        when(mapper.selectFeeItemTotals(eq(COMPANION_ID), any(), any())).thenReturn(List.of());

        CompanionIncomeVO vo = service.income(new DateRangeQuery());

        assertEquals("0.00", vo.getOrderAmount());
        assertEquals("0.00", vo.getActualIncome());
        assertEquals("0.00", vo.getUnsettledAmount());
        assertEquals("0.00", vo.getSettledAmount());
        assertEquals("0.00", vo.getAdvanceTotal());
        assertEquals("0.00", vo.getServiceTotal());
    }

    /* ================================================================== */
    /* 3 · 画像：累计口径 + 准时率 + 擅长服务样本门槛                           */
    /* ================================================================== */

    @Test
    @DisplayName("画像 · 累计完成率与准时率按累计口径算（分母分别是已取消 / 已接单）")
    void profileShouldUseLifetimeStats() {
        when(mapper.selectLifetimeOrderStats(COMPANION_ID))
                .thenReturn(lifetimeStats(128, 7, 8640, "46", "140"));
        when(mapper.selectOnTimeArrivalCount(COMPANION_ID)).thenReturn(129);
        when(mapper.selectProfileScore(COMPANION_ID)).thenReturn(new BigDecimal("4.80"));
        when(mapper.selectDepartmentStats(COMPANION_ID)).thenReturn(List.of());

        CompanionServiceProfileVO vo = service.profile();

        // 128 / (128 + 7) = 94.81%；129 / 140 = 92.14%
        assertEquals("94.81%", vo.getTotalCompletionRate());
        assertEquals("92.14%", vo.getPunctualityRate());
        assertEquals(128, vo.getTotalCompletedOrderCount());
        assertEquals(46, vo.getTotalServedElderCount());
        assertEquals(8640, vo.getTotalServiceDurationMinutes());
    }

    @Test
    @DisplayName("画像 · 没有 ARRIVE 打卡的订单视为不准时（分子不含它）")
    void punctualityRateShouldExcludeOrdersWithoutArrivalCheckin() {
        when(mapper.selectLifetimeOrderStats(COMPANION_ID))
                .thenReturn(lifetimeStats(10, 0, 600, "3", "20"));
        // 只有 18 单准时到院，另外 2 单已接单但没打卡
        when(mapper.selectOnTimeArrivalCount(COMPANION_ID)).thenReturn(18);
        when(mapper.selectProfileScore(COMPANION_ID)).thenReturn(new BigDecimal("4.00"));
        when(mapper.selectDepartmentStats(COMPANION_ID)).thenReturn(List.of());

        CompanionServiceProfileVO vo = service.profile();

        // 18 / 20 = 90.00%（没打卡的 2 单在分母里、不在分子里）
        assertEquals("90.00%", vo.getPunctualityRate());
    }

    @Test
    @DisplayName("画像 · 擅长服务：样本 < 5 单的科室不展示，最多展示 5 个")
    void expertiseShouldSkipSmallSamplesAndCapAtFive() {
        when(mapper.selectLifetimeOrderStats(COMPANION_ID))
                .thenReturn(lifetimeStats(10, 0, 600, "3", "20"));
        when(mapper.selectOnTimeArrivalCount(COMPANION_ID)).thenReturn(20);
        when(mapper.selectProfileScore(COMPANION_ID)).thenReturn(new BigDecimal("4.00"));
        when(mapper.selectDepartmentStats(COMPANION_ID)).thenReturn(List.of(
                Map.of("department", "普通门诊", "cnt", 12L),
                Map.of("department", "复诊陪同", "cnt", 9L),
                Map.of("department", "取药陪同", "cnt", 7L),
                Map.of("department", "检查陪同", "cnt", 6L),
                Map.of("department", "骨科", "cnt", 5L),
                Map.of("department", "心内科", "cnt", 4L),
                Map.of("department", "眼科", "cnt", 1L)));

        CompanionServiceProfileVO vo = service.profile();

        // 样本 4 的「心内科」与样本 1 的「眼科」被门槛挡掉
        assertEquals(5, vo.getExpertise().size());
        assertEquals("普通门诊", vo.getExpertise().get(0).getDepartment());
        assertEquals(12, vo.getExpertise().get(0).getOrderCount());
        assertTrue(vo.getExpertise().stream().noneMatch(e -> "心内科".equals(e.getDepartment())));
        assertTrue(vo.getExpertise().stream().noneMatch(e -> "眼科".equals(e.getDepartment())));
    }

    @Test
    @DisplayName("画像 · 空数据返回 0 与空标签列表，不返回 null")
    void profileShouldReturnZerosOnEmptyData() {
        when(mapper.selectLifetimeOrderStats(COMPANION_ID)).thenReturn(new HashMap<>());
        when(mapper.selectOnTimeArrivalCount(COMPANION_ID)).thenReturn(null);
        when(mapper.selectProfileScore(COMPANION_ID)).thenReturn(null);
        when(mapper.selectDepartmentStats(COMPANION_ID)).thenReturn(List.of());

        CompanionServiceProfileVO vo = service.profile();

        assertEquals(0, vo.getTotalCompletedOrderCount());
        assertEquals(0, vo.getTotalServedElderCount());
        assertEquals("0.00%", vo.getPunctualityRate());
        assertEquals("0.00%", vo.getTotalCompletionRate());
        assertNotNull(vo.getExpertise());
        assertTrue(vo.getExpertise().isEmpty());
    }

    /* ================================================================== */
    /* 4 · 趋势：分桶补 0 + 长度一致 + 非法维度                               */
    /* ================================================================== */

    @Test
    @DisplayName("趋势 · 无数据的桶补 0，categories 与 series.data 长度一致")
    void trendShouldFillEmptyBucketsWithZero() {
        when(mapper.selectDailyOrderCounts(eq(COMPANION_ID), any(), any())).thenReturn(List.of(
                Map.of("d", "2026-10-01", "status", "COMPLETED", "cnt", 3L),
                Map.of("d", "2026-10-01", "status", "REVIEWED", "cnt", 2L),
                Map.of("d", "2026-10-03", "status", "CANCELLED", "cnt", 1L)));

        CompanionTrendQuery query = new CompanionTrendQuery();
        query.setStartDate("2026-10-01");
        query.setEndDate("2026-10-03");
        query.setDimension("ORDER");
        query.setGranularity("DAY");

        CompanionTrendVO vo = service.trend(query);

        assertEquals(3, vo.getCategories().size());
        assertEquals(1, vo.getSeries().size());
        assertEquals(3, vo.getSeries().get(0).getData().size());
        // 10-01 完成 3 + 已评价 2 = 5；10-02 无数据补 0；10-03 只有取消单，不计入完成
        assertEquals(new BigDecimal("5.00"), vo.getSeries().get(0).getData().get(0));
        assertEquals(new BigDecimal("0.00"), vo.getSeries().get(0).getData().get(1));
        assertEquals(new BigDecimal("0.00"), vo.getSeries().get(0).getData().get(2));
    }

    @Test
    @DisplayName("趋势 · 评分维度在周粒度下按评价条数加权（不是日均值的平均）")
    void ratingTrendShouldWeightByReviewCount() {
        when(mapper.selectDailyRating(eq(COMPANION_ID), any(), any())).thenReturn(List.of(
                Map.of("d", "2026-10-01", "avgScore", new BigDecimal("5.0"), "cnt", 4L),
                Map.of("d", "2026-10-02", "avgScore", new BigDecimal("1.0"), "cnt", 1L)));

        CompanionTrendQuery query = new CompanionTrendQuery();
        query.setStartDate("2026-10-01");
        query.setEndDate("2026-10-02");
        query.setDimension("RATING");
        // 周粒度：两天落在同一个桶里，才谈得上「日均值能不能平均」
        query.setGranularity("WEEK");

        CompanionTrendVO vo = service.trend(query);

        assertEquals(1, vo.getCategories().size());
        // (5.0×4 + 1.0×1) / 5 = 4.20；若按日均值的平均会得到 3.00
        assertEquals(new BigDecimal("4.20"), vo.getSeries().get(0).getData().get(0));
    }

    @Test
    @DisplayName("趋势 · 评分维度在日粒度下每天自成一块（不跨日加权）")
    void ratingTrendShouldKeepDailyBucketsSeparate() {
        when(mapper.selectDailyRating(eq(COMPANION_ID), any(), any())).thenReturn(List.of(
                Map.of("d", "2026-10-01", "avgScore", new BigDecimal("5.0"), "cnt", 4L),
                Map.of("d", "2026-10-02", "avgScore", new BigDecimal("1.0"), "cnt", 1L)));

        CompanionTrendQuery query = new CompanionTrendQuery();
        query.setStartDate("2026-10-01");
        query.setEndDate("2026-10-02");
        query.setDimension("RATING");
        query.setGranularity("DAY");

        CompanionTrendVO vo = service.trend(query);

        assertEquals(2, vo.getCategories().size());
        assertEquals(new BigDecimal("5.00"), vo.getSeries().get(0).getData().get(0));
        assertEquals(new BigDecimal("1.00"), vo.getSeries().get(0).getData().get(1));
    }

    @Test
    @DisplayName("趋势 · 空数据时返回等长的空序列而不是空指针")
    void trendShouldReturnAlignedEmptySeriesOnNoData() {
        when(mapper.selectDailyOrderCounts(eq(COMPANION_ID), any(), any())).thenReturn(List.of());

        CompanionTrendQuery query = new CompanionTrendQuery();
        query.setStartDate("2026-10-01");
        query.setEndDate("2026-10-02");

        CompanionTrendVO vo = service.trend(query);

        assertEquals(2, vo.getCategories().size());
        assertEquals(2, vo.getSeries().get(0).getData().size());
        assertEquals("ORDER", vo.getDimension());
        assertEquals("DAY", vo.getGranularity());
    }

    @Test
    @DisplayName("趋势 · 非法维度抛 BusinessException（400），不静默降级成 ORDER")
    void trendShouldRejectInvalidDimension() {
        CompanionTrendQuery query = new CompanionTrendQuery();
        query.setDimension("WHATEVER");

        BusinessException ex = assertThrows(BusinessException.class, () -> service.trend(query));
        assertEquals(400, ex.getCode());
        // 非法维度时不应该去查库
        verify(mapper, never()).selectDailyOrderCounts(anyLong(), any(), any());
    }

    @Test
    @DisplayName("趋势 · 非法粒度抛 BusinessException（400）")
    void trendShouldRejectInvalidGranularity() {
        CompanionTrendQuery query = new CompanionTrendQuery();
        query.setGranularity("HOUR");

        BusinessException ex = assertThrows(BusinessException.class, () -> service.trend(query));
        assertEquals(400, ex.getCode());
    }

    /* ================================================================== */
    /* 5 · 身份：只认登录态                                                  */
    /* ================================================================== */

    @Test
    @DisplayName("概览 · 只用登录态里的陪诊员 ID，忽略任何入参")
    void overviewShouldAlwaysUseLoggedInCompanionId() {
        when(mapper.selectOrderStats(eq(COMPANION_ID), any(), any()))
                .thenReturn(stats(28, 2, 2520, "3680.00", "4000.00"));
        when(mapper.selectProfileScore(COMPANION_ID)).thenReturn(new BigDecimal("4.80"));

        DateRangeQuery query = new DateRangeQuery();
        query.setStartDate("2026-10-01");
        query.setEndDate("2026-10-20");
        service.overview(query);

        verify(mapper).selectOrderStats(eq(COMPANION_ID), any(), any());
        verify(mapper, never()).selectOrderStats(eq(999L), any(), any());
    }

    /* ================================================================== */
    /* 工具                                                                */
    /* ================================================================== */

    /** 构造一行 mapper 聚合结果 */
    private static Map<String, Object> stats(long completed, long cancelled, long serviceMinutes,
                                             String income, String orderAmount) {
        Map<String, Object> map = new HashMap<>();
        map.put("completedCount", completed);
        map.put("cancelledCount", cancelled);
        map.put("serviceMinutes", serviceMinutes);
        map.put("income", new BigDecimal(income));
        map.put("orderAmount", new BigDecimal(orderAmount));
        return map;
    }

    /** 构造累计口径的一行聚合结果 */
    private static Map<String, Object> lifetimeStats(long completed, long cancelled, long serviceMinutes,
                                                     String servedElderCount, String acceptedCount) {
        Map<String, Object> map = new HashMap<>();
        map.put("totalCompletedCount", completed);
        map.put("totalCancelledCount", cancelled);
        map.put("totalServiceMinutes", serviceMinutes);
        map.put("totalServedElderCount", Long.parseLong(servedElderCount));
        map.put("acceptedCount", Long.parseLong(acceptedCount));
        return map;
    }

}

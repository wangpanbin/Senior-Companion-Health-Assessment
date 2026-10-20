package org.company.nianglin.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.company.nianglin.constant.MedicationBucket;
import org.company.nianglin.dto.DateRangeQuery;
import org.company.nianglin.dto.OperationsTrendQuery;
import org.company.nianglin.entity.CompanionOrder;
import org.company.nianglin.entity.Complaint;
import org.company.nianglin.entity.MedicationTask;
import org.company.nianglin.entity.OrderReview;
import org.company.nianglin.mapper.CompanionOrderMapper;
import org.company.nianglin.mapper.ComplaintMapper;
import org.company.nianglin.mapper.MedicationTaskMapper;
import org.company.nianglin.mapper.OrderReviewMapper;
import org.company.nianglin.vo.ChartDataVO;
import org.company.nianglin.vo.FulfillmentSummaryVO;
import org.company.nianglin.vo.OperationsExceptionItemVO;
import org.company.nianglin.vo.OperationsExceptionVO;
import org.company.nianglin.vo.OperationsHealthVO;
import org.company.nianglin.vo.OperationsOverviewVO;
import org.company.nianglin.vo.OperationsTrendVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 平台运营驾驶舱的<b>真库</b>一致性测试（V3.5 · T6 · 真库层）。
 *
 * <h3>为什么单测之外还要真库这一层</h3>
 *
 * <p>Mockito 单测锁的是「Service 拿到这些数之后怎么算」，
 * 而聚合 SQL 本身对不对（{@code deleted = 0} 写没写、{@code INTERVAL} 用反没、
 * 三桶判定有没有写歪）只有真跑一遍才知道。</p>
 *
 * <p>所以这里的断言方式很朴素也很硬：<b>用 Java 逐条遍历订单算一遍，
 * 再和接口返回的数字比</b>。两套实现互为对照，任何一边漂移都会对不上。</p>
 *
 * <h3>区间为什么不用默认的最近 30 天</h3>
 *
 * <p>种子数据的下单时间落在 2026-08-18 ~ 2026-09-18，而「今天」是 2026-10-20 ——
 * 默认的最近 30 天区间里一条订单都没有，测出来的全是 0，断言等于没写。
 * 所以这里显式给一段覆盖全部种子的区间。</p>
 *
 * @author 银龄伴诊团队
 * @since V3.5
 */
@SpringBootTest
@DisplayName("V3.5 真库一致性：聚合 SQL 与逐条 Java 统计必须一致")
class OperationsRealDbTest {

    /** 覆盖全部种子订单的区间 */
    private static final String START_DATE = "2026-08-01";
    private static final String END_DATE = "2026-09-30";

    /** 「按时完成」的判定窗口（与默认配置一致） */
    private static final int FULFILLMENT_WINDOW_MINUTES = 240;

    @Autowired
    private OperationsService service;

    @Autowired
    private AdminService adminService;

    @Autowired
    private CompanionOrderMapper orderMapper;

    @Autowired
    private ComplaintMapper complaintMapper;

    @Autowired
    private OrderReviewMapper reviewMapper;

    @Autowired
    private MedicationTaskMapper medicationTaskMapper;

    /* ================================================================== */
    /* 1 · 概览                                                            */
    /* ================================================================== */

    @Test
    @DisplayName("概览 · 各状态订单数与履约率必须与逐条统计一致")
    void overviewShouldMatchRowByRowAggregation() {
        OperationsOverviewVO vo = service.overview(query());

        List<CompanionOrder> orders = ordersInRange();

        long completed = orders.stream().filter(o -> isDone(o.getStatus())).count();
        long cancelled = orders.stream().filter(o -> "CANCELLED".equals(o.getStatus())).count();
        long inService = orders.stream().filter(o -> "IN_SERVICE".equals(o.getStatus())).count();
        long pending = orders.stream().filter(o -> "PENDING".equals(o.getStatus())).count();

        assertEquals((int) completed, vo.getCompletedOrderCount(), "完成订单数应与逐条统计一致");
        assertEquals((int) inService, vo.getInServiceCount(), "服务中订单数应与逐条统计一致");
        assertEquals((int) pending, vo.getPendingCount(), "待接单订单数应与逐条统计一致");
        assertEquals(ChartDataVO.percent(completed, completed + cancelled), vo.getFulfillmentRate(),
                "履约率应与逐条统计一致");
    }

    @Test
    @DisplayName("概览 · 投诉率的分子是去重后的投诉订单数")
    void overviewComplaintRateShouldMatchDistinctComplaintOrders() {
        OperationsOverviewVO vo = service.overview(query());

        List<CompanionOrder> orders = ordersInRange();
        long complaintOrders = complaintsInRange().stream()
                .map(Complaint::getOrderId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .count();

        assertEquals(ChartDataVO.percent(complaintOrders, orders.size()), vo.getComplaintRate(),
                "投诉率应与逐条统计一致");
    }

    @Test
    @DisplayName("概览 · 平均评分与逐条统计一致（只算有效评价）")
    void overviewAverageScoreShouldMatchRowByRowAggregation() {
        OperationsOverviewVO vo = service.overview(query());

        List<OrderReview> reviews = reviewsInRange();
        BigDecimal sum = reviews.stream()
                .map(OrderReview::getScore)
                .filter(Objects::nonNull)
                .map(score -> BigDecimal.valueOf(score.longValue()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal expected = reviews.isEmpty()
                ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
                : sum.divide(BigDecimal.valueOf(reviews.size()), 2, RoundingMode.HALF_UP);

        assertEquals(expected.toPlainString(), vo.getAverageScore(), "平均评分应与逐条统计一致");
    }

    /* ================================================================== */
    /* 2 · 健康度：完成率（按时）                                           */
    /* ================================================================== */

    @Test
    @DisplayName("健康度 · 完成率（按时）与履约率分别与逐条统计一致，且两者不是同一个数")
    void healthCompletionRateShouldMatchRowByRowAggregation() {
        OperationsHealthVO vo = service.health(query());

        List<CompanionOrder> orders = ordersInRange();
        long completed = orders.stream().filter(o -> isDone(o.getStatus())).count();
        long onTime = orders.stream()
                .filter(o -> isDone(o.getStatus()))
                .filter(o -> o.getFinishTime() != null && o.getVisitTime() != null)
                .filter(o -> !o.getFinishTime().isAfter(o.getVisitTime().plusMinutes(FULFILLMENT_WINDOW_MINUTES)))
                .count();

        assertEquals(ChartDataVO.percent(onTime, completed), vo.getCompletionRate(),
                "完成率（按时）应与逐条统计一致");
        assertEquals(ChartDataVO.percent(completed, completed + cancelledOf(orders)), vo.getFulfillmentRate(),
                "履约率应与逐条统计一致");
    }

    /* ================================================================== */
    /* 3 · 用药漏服三桶与服药日历同源                                      */
    /* ================================================================== */

    @Test
    @DisplayName("用药漏服 · 漏服桶与 MedicationBucket 的逐条判定一致")
    void missedMedicationShouldMatchBucketEnumeration() {
        OperationsExceptionVO vo = service.exceptions(query());

        long missed = tasksInRange().stream()
                .filter(t -> MedicationBucket.of(t.getStatus(), wasMissed(t)) == MedicationBucket.MISSED)
                .count();

        int itemCount = itemCount(vo, "MEDICATION_MISSED");
        assertEquals((int) missed, itemCount,
                "漏服任务数应与 MedicationBucket 的逐条判定一致（ADR-0014）");
    }

    /* ================================================================== */
    /* 4 · 异常中心与履约风险中心同源（真库 parity）                        */
    /* ================================================================== */

    @Test
    @DisplayName("异常中心 · 前两项与履约风险中心一致，且与独立统计一致")
    void exceptionsShouldBeParityWithFulfillmentRiskCenter() {
        OperationsExceptionVO vo = service.exceptions(query());
        FulfillmentSummaryVO summary = adminService.fulfillmentSummary();

        assertEquals(summary.getManualRequired(), itemCount(vo, "MANUAL_REQUIRED"),
                "需人工介入订单数必须与履约风险中心一致");
        assertEquals(summary.getWarning() + summary.getUrgent(), itemCount(vo, "MATCH_WARNING"),
                "超匹配预警时间订单数必须与履约风险中心一致");

        // 独立算一遍：只统计待接单订单的风险档位
        long manualRequired = ordersInRange().stream()
                .filter(o -> "PENDING".equals(o.getStatus()))
                .filter(o -> "MANUAL_REQUIRED".equals(o.getFulfillmentRisk()))
                .count();
        long raised = ordersInRange().stream()
                .filter(o -> "PENDING".equals(o.getStatus()))
                .filter(o -> "WARNING".equals(o.getFulfillmentRisk()) || "URGENT".equals(o.getFulfillmentRisk()))
                .count();

        assertEquals((int) manualRequired, itemCount(vo, "MANUAL_REQUIRED"),
                "需人工介入订单数必须与「PENDING + MANUAL_REQUIRED」一致");
        assertEquals((int) raised, itemCount(vo, "MATCH_WARNING"),
                "超匹配预警时间订单数必须与「PENDING + WARNING/URGENT」一致");
    }

    /* ================================================================== */
    /* 5 · 趋势                                                            */
    /* ================================================================== */

    @Test
    @DisplayName("趋势 · ORDER 维度的每桶之和等于区间订单总数")
    void orderTrendShouldSumToTotalOrders() {
        OperationsTrendQuery query = new OperationsTrendQuery();
        query.setDimension("ORDER");
        query.setRange("CUSTOM");
        query.setStartDate(START_DATE);
        query.setEndDate(END_DATE);

        OperationsTrendVO vo = service.trend(query);

        assertEquals(vo.getCategories().size(), vo.getSeries().get(0).getData().size(),
                "categories 与 series.data 长度必须严格一致");
        long sum = vo.getSeries().get(0).getData().stream()
                .mapToLong(v -> v.longValue())
                .sum();
        assertEquals((long) ordersInRange().size(), sum,
                "趋势各桶之和应等于区间订单总数");
        assertTrue(vo.getCategories().size() >= 30, "覆盖两个月的区间至少 30 个桶");
    }

    /* ================================================================== */
    /* 工具                                                                */
    /* ================================================================== */

    /** 「已完成」= COMPLETED + REVIEWED（M10 起的铁律） */
    private static boolean isDone(String status) {
        return "COMPLETED".equals(status) || "REVIEWED".equals(status);
    }

    /** {@code was_missed} 列是 TINYINT，判定口径与 {@link MedicationBucket} 的布尔参数一致 */
    private static Boolean wasMissed(MedicationTask task) {
        return task.getWasMissed() != null && task.getWasMissed() == 1;
    }

    private static long cancelledOf(List<CompanionOrder> orders) {
        return orders.stream().filter(o -> "CANCELLED".equals(o.getStatus())).count();
    }

    private List<CompanionOrder> ordersInRange() {
        return orderMapper.selectList(Wrappers.<CompanionOrder>lambdaQuery()
                .between(CompanionOrder::getCreateTime, startTime(), endTime()));
    }

    private List<Complaint> complaintsInRange() {
        return complaintMapper.selectList(Wrappers.<Complaint>lambdaQuery()
                .between(Complaint::getCreateTime, startTime(), endTime()));
    }

    private List<OrderReview> reviewsInRange() {
        return reviewMapper.selectList(Wrappers.<OrderReview>lambdaQuery()
                .eq(OrderReview::getIsValid, 1)
                .between(OrderReview::getCreateTime, startTime(), endTime()));
    }

    private List<MedicationTask> tasksInRange() {
        return medicationTaskMapper.selectList(Wrappers.<MedicationTask>lambdaQuery()
                .between(MedicationTask::getPlanTime, startTime(), endTime()));
    }

    private static LocalDateTime startTime() {
        return LocalDateTime.parse(START_DATE + "T00:00:00");
    }

    private static LocalDateTime endTime() {
        return LocalDateTime.parse(END_DATE + "T23:59:59");
    }

    private static DateRangeQuery query() {
        DateRangeQuery query = new DateRangeQuery();
        query.setStartDate(START_DATE);
        query.setEndDate(END_DATE);
        return query;
    }

    private static int itemCount(OperationsExceptionVO vo, String type) {
        for (OperationsExceptionItemVO item : vo.getItems()) {
            if (type.equals(item.getType())) {
                return item.getCount();
            }
        }
        throw new AssertionError("异常中心没有返回类型：" + type);
    }
}

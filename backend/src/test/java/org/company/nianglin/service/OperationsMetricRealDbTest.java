package org.company.nianglin.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.company.nianglin.common.DateRange;
import org.company.nianglin.constant.MedicationBucket;
import org.company.nianglin.dto.DateRangeQuery;
import org.company.nianglin.dto.OperationsEffectQuery;
import org.company.nianglin.entity.CompanionOrder;
import org.company.nianglin.entity.MedicationTask;
import org.company.nianglin.mapper.CompanionOrderMapper;
import org.company.nianglin.mapper.MedicationTaskMapper;
import org.company.nianglin.service.support.metrics.MetricCalculator;
import org.company.nianglin.service.support.metrics.MetricCalculatorRegistry;
import org.company.nianglin.vo.ChartDataVO;
import org.company.nianglin.vo.OperationsEffectVO;
import org.company.nianglin.vo.OperationsOverviewVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V3.6 指标计算器的<b>真库</b>一致性测试（V3.6 · T3 / T6 · T7 · 真库层）。
 *
 * <h3>这个测试在防什么</h3>
 *
 * <p>spec §四写得很直白：「让驾驶舱的履约率与 V3.6 的履约率用<b>不同公式</b>
 * → parity 测试变红」。所以这里做的是<b>三方对账</b>：</p>
 * <ul>
 *   <li>计算器算出来的值；</li>
 *   <li>驾驶舱（V3.5）算出来的值；</li>
 *   <li>用实体查询逐条遍历订单算出来的值。</li>
 * </ul>
 *
 * <p>三者必须相等 —— 只对前两者的话，公式写在同一个地方也算「一致」，
 * 而一致的口径本身可能是错的。</p>
 *
 * @author 银龄伴诊团队
 * @since V3.6
 */
@SpringBootTest
@DisplayName("V3.6 真库一致性：计算器 / 驾驶舱 / 逐条 Java 统计三方对账")
class OperationsMetricRealDbTest {

    /** 覆盖全部种子订单的区间 */
    private static final String START_DATE = "2026-08-01";
    private static final String END_DATE = "2026-09-30";

    @Autowired
    private OperationsService service;

    @Autowired
    private MetricCalculatorRegistry registry;

    @Autowired
    private CompanionOrderMapper orderMapper;

    @Autowired
    private MedicationTaskMapper medicationTaskMapper;

    /* ================================================================== */
    /* 1 · 履约率三方对账                                                 */
    /* ================================================================== */

    @Test
    @DisplayName("履约率 · 计算器 == 驾驶舱概览 == 逐条 Java 统计")
    void fulfillmentRateShouldMatchCockpitAndRowByRow() {
        MetricCalculator calculator = registry.find("operations.fulfillment_rate");
        assertNotNull(calculator, "履约率必须已注册计算器");

        String fromCalculator = calculator.calculate(range());
        OperationsOverviewVO overview = service.overview(query());

        assertEquals(overview.getFulfillmentRate(), fromCalculator,
                "计算器与驾驶舱概览的履约率必须一致（同一个口径）");

        List<CompanionOrder> orders = ordersInRange();
        long completed = orders.stream().filter(o -> isDone(o.getStatus())).count();
        long cancelled = orders.stream().filter(o -> "CANCELLED".equals(o.getStatus())).count();

        assertEquals(ChartDataVO.percent(completed, completed + cancelled), fromCalculator,
                "履约率必须与逐条统计一致");
    }

    @Test
    @DisplayName("完成率（按时）+ 超时率 = 100%（两个指标取同一份聚合，不会各算各的）")
    void completionRateAndTimeoutRateShouldBeComplementary() {
        MetricCalculator completion = registry.find("operations.completion_rate");
        MetricCalculator timeout = registry.find("operations.timeout_rate");
        assertNotNull(completion);
        assertNotNull(timeout);

        String completionValue = completion.calculate(range());
        String timeoutValue = timeout.calculate(range());

        double completionNumber = Double.parseDouble(completionValue.replace("%", ""));
        double timeoutNumber = Double.parseDouble(timeoutValue.replace("%", ""));

        assertEquals(100.0D, completionNumber + timeoutNumber, 0.01D,
                "完成率（按时）与超时率必须互补：两个指标取的是同一份聚合");
    }

    /* ================================================================== */
    /* 2 · 用药三桶与服药日历同源                                          */
    /* ================================================================== */

    @Test
    @DisplayName("漏服率 · 分子与 MedicationBucket 的逐条判定一致，分母与任务总数一致")
    void medicationMissedRateShouldMatchBucketEnumeration() {
        MetricCalculator missedRate = registry.find("operations.medication_missed_rate");
        MetricCalculator taskCount = registry.find("operations.medication_task_count");
        assertNotNull(missedRate);
        assertNotNull(taskCount);

        List<MedicationTask> tasks = tasksInRange();
        long total = tasks.size();
        long missed = tasks.stream()
                .filter(t -> MedicationBucket.of(t.getStatus(), wasMissed(t)) == MedicationBucket.MISSED)
                .count();

        assertEquals(String.valueOf(total), taskCount.calculate(range()),
                "用药任务数必须与逐条统计一致");
        assertEquals(ChartDataVO.percent(missed, total), missedRate.calculate(range()),
                "漏服率必须与 MedicationBucket 的逐条判定一致（ADR-0014）");
    }

    /* ================================================================== */
    /* 3 · 效果对比与计算器同源                                           */
    /* ================================================================== */

    @Test
    @DisplayName("效果对比 · 两期数值与同一个计算器直接算的结果一致")
    void effectShouldUseTheSameCalculatorAsDirectCall() {
        MetricCalculator calculator = registry.find("operations.fulfillment_rate");
        assertNotNull(calculator);

        OperationsEffectQuery query = new OperationsEffectQuery();
        query.setMetric("operations.fulfillment_rate");
        query.setFrom("2026-08-01");
        query.setTo("2026-08-15");
        query.setCompareFrom("2026-07-17");
        query.setCompareTo("2026-07-31");

        OperationsEffectVO vo = service.effect(query);

        assertEquals(calculator.calculate(new DateRange(LocalDate.parse("2026-08-01"),
                LocalDate.parse("2026-08-15"))), vo.getCurrent().getValue(),
                "后一期的值必须就是同一个计算器算出来的");
        assertEquals(calculator.calculate(new DateRange(LocalDate.parse("2026-07-17"),
                LocalDate.parse("2026-07-31"))), vo.getPrevious().getValue(),
                "前一期的值必须就是同一个计算器算出来的");
        assertNotNull(vo.getDelta());
    }

    @Test
    @DisplayName("真库上确实有数据（否则这些断言等于没写）")
    void seedDataShouldNotBeEmpty() {
        assertTrue(ordersInRange().size() > 0, "区间里没有订单 —— 指标算出来全是 0");
        assertTrue(tasksInRange().size() > 0, "区间里没有用药任务 —— 用药类指标算出来全是 0");
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

    private List<CompanionOrder> ordersInRange() {
        return orderMapper.selectList(Wrappers.<CompanionOrder>lambdaQuery()
                .between(CompanionOrder::getCreateTime, startTime(), endTime()));
    }

    private List<MedicationTask> tasksInRange() {
        return medicationTaskMapper.selectList(Wrappers.<MedicationTask>lambdaQuery()
                .between(MedicationTask::getPlanTime, startTime(), endTime()));
    }

    private static DateRange range() {
        return new DateRange(LocalDate.parse(START_DATE), LocalDate.parse(END_DATE));
    }

    private static DateRangeQuery query() {
        DateRangeQuery query = new DateRangeQuery();
        query.setStartDate(START_DATE);
        query.setEndDate(END_DATE);
        return query;
    }

    private static LocalDateTime startTime() {
        return LocalDate.parse(START_DATE).atStartOfDay();
    }

    private static LocalDateTime endTime() {
        return LocalDate.parse(END_DATE).atTime(23, 59, 59);
    }
}

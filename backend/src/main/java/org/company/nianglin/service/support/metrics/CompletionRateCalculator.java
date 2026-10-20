package org.company.nianglin.service.support.metrics;

import org.company.nianglin.common.DateRange;
import org.company.nianglin.config.OperationsProperties;
import org.company.nianglin.mapper.OperationsMapper;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 完成率（按时）计算器（V3.6 · T3）。
 *
 * <h3>公式</h3>
 *
 * <p>按时完成单数 ÷ 已完成单数。「按时」= {@code finish_time <= visit_time + 窗口}，
 * 窗口取自 {@code nianglin.operations.fulfillment-window-minutes}（默认 240 分钟）。</p>
 *
 * <p>⚠️ 它与 {@link FulfillmentRateCalculator} <b>不是同一个指标</b>：
 * 履约率 = 完成 ÷（完成 + 取消），完成率（按时）= 按时完成 ÷ 已完成。
 * 两个名字一个数是「口径分裂」的另一种形态（偏差 D-3.5-1）。</p>
 *
 * @author 银龄伴诊团队
 * @since V3.6
 */
@Component
public class CompletionRateCalculator extends AbstractMetricCalculator {

    public CompletionRateCalculator(OperationsMapper operationsMapper, OperationsProperties operationsProperties) {
        super(operationsMapper, operationsProperties);
    }

    @Override
    public String metricKey() {
        return "operations.completion_rate";
    }

    @Override
    public String calculate(DateRange range) {
        Map<String, Object> stats = orderStats(range);
        long completed = longOf(stats, "completedCount");
        long onTime = longOf(stats, "onTimeCompletedCount");
        return percent(onTime, completed);
    }
}

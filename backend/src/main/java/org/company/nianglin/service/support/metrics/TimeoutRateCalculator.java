package org.company.nianglin.service.support.metrics;

import org.company.nianglin.common.DateRange;
import org.company.nianglin.config.OperationsProperties;
import org.company.nianglin.mapper.OperationsMapper;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 超时率计算器（V3.6 · T3 · T6 补登）。
 *
 * <h3>公式</h3>
 *
 * <p>已完成订单中「未在承诺时长内完成」的单数 ÷ 已完成单数。</p>
 *
 * <p>「承诺时长」与 {@link CompletionRateCalculator} 的「按时」是同一个窗口
 * （{@code nianglin.operations.fulfillment-window-minutes}，默认 240 分钟），
 * 所以本指标 = 1 − 完成率（按时）。两者取<b>同一份聚合</b>，
 * 不会出现「完成率 88% 但超时率 13%」这种加不到 100% 的情况。</p>
 *
 * @author 银龄伴诊团队
 * @since V3.6
 */
@Component
public class TimeoutRateCalculator extends AbstractMetricCalculator {

    public TimeoutRateCalculator(OperationsMapper operationsMapper, OperationsProperties operationsProperties) {
        super(operationsMapper, operationsProperties);
    }

    @Override
    public String metricKey() {
        return "operations.timeout_rate";
    }

    @Override
    public String calculate(DateRange range) {
        Map<String, Object> stats = orderStats(range);
        long completed = longOf(stats, "completedCount");
        long onTime = longOf(stats, "onTimeCompletedCount");
        long timeout = Math.max(0L, completed - onTime);
        return percent(timeout, completed);
    }
}

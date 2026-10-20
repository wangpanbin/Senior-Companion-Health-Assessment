package org.company.nianglin.service.support.metrics;

import org.company.nianglin.common.DateRange;
import org.company.nianglin.config.OperationsProperties;
import org.company.nianglin.mapper.OperationsMapper;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 平均接单时间计算器（V3.6 · T3）。
 *
 * <h3>公式</h3>
 *
 * <p>已接单订单从下单到接单的耗时均值（{@code AVG(TIMESTAMPDIFF(MINUTE, create_time, accept_time))}），
 * 与 V3.5 驾驶舱「概览 / 健康度」同源。</p>
 *
 * <p>没有已接单订单时记 {@code 0}（不是 {@code null}、不是报错）。</p>
 *
 * @author 银龄伴诊团队
 * @since V3.6
 */
@Component
public class AvgAcceptMinutesCalculator extends AbstractMetricCalculator {

    public AvgAcceptMinutesCalculator(OperationsMapper operationsMapper, OperationsProperties operationsProperties) {
        super(operationsMapper, operationsProperties);
    }

    @Override
    public String metricKey() {
        return "operations.avg_accept_minutes";
    }

    @Override
    public String calculate(DateRange range) {
        Map<String, Object> stats = orderStats(range);
        return count(rounded(stats == null ? null : stats.get("avgAcceptMinutes")));
    }
}

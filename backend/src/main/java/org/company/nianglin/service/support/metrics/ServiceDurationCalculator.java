package org.company.nianglin.service.support.metrics;

import org.company.nianglin.common.DateRange;
import org.company.nianglin.config.OperationsProperties;
import org.company.nianglin.mapper.OperationsMapper;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 平均服务时长计算器（V3.6 · T3 · T6 补登）。
 *
 * <h3>公式</h3>
 *
 * <p>{@code AVG(TIMESTAMPDIFF(MINUTE, start_time, finish_time))}，
 * 仅「已完成」（COMPLETED + REVIEWED）且起止时间均非空的订单。</p>
 *
 * <p>⚠️ 与 {@code companion.workbench.service_duration}（区间服务时长<b>合计</b>）
 * 是两个指标：一个看均值、一个看总量，公式不同，不共用实现。</p>
 *
 * @author 银龄伴诊团队
 * @since V3.6
 */
@Component
public class ServiceDurationCalculator extends AbstractMetricCalculator {

    public ServiceDurationCalculator(OperationsMapper operationsMapper, OperationsProperties operationsProperties) {
        super(operationsMapper, operationsProperties);
    }

    @Override
    public String metricKey() {
        return "operations.service_duration_minutes";
    }

    @Override
    public String calculate(DateRange range) {
        Map<String, Object> stats = orderStats(range);
        return fixed(stats == null ? null : stats.get("avgServiceDurationMinutes"));
    }
}

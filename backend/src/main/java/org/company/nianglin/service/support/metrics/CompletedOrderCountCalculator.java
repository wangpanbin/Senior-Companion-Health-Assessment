package org.company.nianglin.service.support.metrics;

import org.company.nianglin.common.DateRange;
import org.company.nianglin.config.OperationsProperties;
import org.company.nianglin.mapper.OperationsMapper;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 区间完成订单数计算器（V3.6 · T3）。
 *
 * <h3>公式</h3>
 *
 * <p>{@code COUNT(status IN ('COMPLETED', 'REVIEWED'))} ——
 * 「已完成」= COMPLETED + REVIEWED，把已评价的单排除会让完成率随着用户评价而下降。</p>
 *
 * @author 银龄伴诊团队
 * @since V3.6
 */
@Component
public class CompletedOrderCountCalculator extends AbstractMetricCalculator {

    public CompletedOrderCountCalculator(OperationsMapper operationsMapper, OperationsProperties operationsProperties) {
        super(operationsMapper, operationsProperties);
    }

    @Override
    public String metricKey() {
        return "operations.completed_order_count";
    }

    @Override
    public String calculate(DateRange range) {
        Map<String, Object> stats = orderStats(range);
        return count(longOf(stats, "completedCount"));
    }
}

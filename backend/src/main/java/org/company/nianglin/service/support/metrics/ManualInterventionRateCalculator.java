package org.company.nianglin.service.support.metrics;

import org.company.nianglin.common.DateRange;
import org.company.nianglin.config.OperationsProperties;
import org.company.nianglin.mapper.OperationsMapper;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 人工介入率计算器（V3.6 · T3 · T6 补登）。
 *
 * <h3>公式</h3>
 *
 * <p>{@code fulfillment_risk = 'MANUAL_REQUIRED'} 的订单数 ÷ 区间订单数。</p>
 *
 * <p>「需人工介入」这个判定沿用 V3.1 的履约风险分级（{@code FulfillmentRiskEvaluator}），
 * 本计算器<b>不另建一套判定</b>。</p>
 *
 * @author 银龄伴诊团队
 * @since V3.6
 */
@Component
public class ManualInterventionRateCalculator extends AbstractMetricCalculator {

    public ManualInterventionRateCalculator(OperationsMapper operationsMapper, OperationsProperties operationsProperties) {
        super(operationsMapper, operationsProperties);
    }

    @Override
    public String metricKey() {
        return "operations.manual_intervention_rate";
    }

    @Override
    public String calculate(DateRange range) {
        Map<String, Object> stats = orderStats(range);
        long total = longOf(stats, "totalCount");
        long manual = longOf(stats, "manualRequiredCount");
        return percent(manual, total);
    }
}

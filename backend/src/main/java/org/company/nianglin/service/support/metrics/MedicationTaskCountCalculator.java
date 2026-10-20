package org.company.nianglin.service.support.metrics;

import org.company.nianglin.common.DateRange;
import org.company.nianglin.config.OperationsProperties;
import org.company.nianglin.mapper.OperationsMapper;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 用药任务数计算器（V3.6 · T3 · T6 补登）。
 *
 * <h3>公式</h3>
 *
 * <p>{@code COUNT(*)}（区间内的服药任务总数）。</p>
 *
 * <p>三桶判定与漏服率取<b>同一次聚合</b>（{@code selectMedicationAggregates}），
 * 所以「用药任务数」永远是漏服率的分母，两个数不会各算各的。</p>
 *
 * @author 银龄伴诊团队
 * @since V3.6
 */
@Component
public class MedicationTaskCountCalculator extends AbstractMetricCalculator {

    public MedicationTaskCountCalculator(OperationsMapper operationsMapper, OperationsProperties operationsProperties) {
        super(operationsMapper, operationsProperties);
    }

    @Override
    public String metricKey() {
        return "operations.medication_task_count";
    }

    @Override
    public String calculate(DateRange range) {
        Map<String, Object> stats = medicationStats(range);
        return count(longOf(stats, "total"));
    }
}

package org.company.nianglin.service.support.metrics;

import org.company.nianglin.common.DateRange;
import org.company.nianglin.config.OperationsProperties;
import org.company.nianglin.mapper.OperationsMapper;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 漏服率计算器（V3.6 · T3 · T6 补登）。
 *
 * <h3>公式</h3>
 *
 * <p>漏服桶数 ÷ 用药任务总数。漏服桶由 {@code MedicationBucketSql.CASE_BUCKET}
 * 唯一裁定（{@code status = 'MISSED' 或 was_missed = 1}），
 * 与服药日历、异常中心的「用药漏服异常」<b>同源</b>（ADR-0014）。</p>
 *
 * @author 银龄伴诊团队
 * @since V3.6
 */
@Component
public class MedicationMissedRateCalculator extends AbstractMetricCalculator {

    public MedicationMissedRateCalculator(OperationsMapper operationsMapper, OperationsProperties operationsProperties) {
        super(operationsMapper, operationsProperties);
    }

    @Override
    public String metricKey() {
        return "operations.medication_missed_rate";
    }

    @Override
    public String calculate(DateRange range) {
        Map<String, Object> stats = medicationStats(range);
        long total = longOf(stats, "total");
        long missed = longOf(stats, "missed");
        return percent(missed, total);
    }
}

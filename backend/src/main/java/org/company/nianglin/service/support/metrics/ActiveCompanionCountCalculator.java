package org.company.nianglin.service.support.metrics;

import org.company.nianglin.common.DateRange;
import org.company.nianglin.config.OperationsProperties;
import org.company.nianglin.mapper.OperationsMapper;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 活跃陪诊员数计算器（V3.6 · T3）。
 *
 * <h3>公式</h3>
 *
 * <p>{@code COUNT(DISTINCT companion_id)} —— 区间内<b>接过单</b>的陪诊员人数（去重）。</p>
 *
 * <h3>为什么同时挂着 {@code operations.companion_count}</h3>
 *
 * <p>V3.6 补登的「陪诊员数」与本指标<b>公式逐字相同</b>（迭代计划 §48 的清单里
 * 「陪诊员数」与「活跃陪诊员数」指向同一件事）。决策 6.2 要求「一个口径」，
 * 所以两者共用这一个实现，而不是各写一份聚合 SQL。</p>
 *
 * @author 银龄伴诊团队
 * @since V3.6
 */
@Component
public class ActiveCompanionCountCalculator extends AbstractMetricCalculator {

    public ActiveCompanionCountCalculator(OperationsMapper operationsMapper, OperationsProperties operationsProperties) {
        super(operationsMapper, operationsProperties);
    }

    @Override
    public String metricKey() {
        return "operations.active_companion_count";
    }

    @Override
    public List<String> aliasKeys() {
        return List.of("operations.companion_count");
    }

    @Override
    public String calculate(DateRange range) {
        Map<String, Object> stats = operationsMapper.selectCompanionStats(range.startTime(), range.endTime());
        return count(longOf(stats, "activeCompanionCount"));
    }
}

package org.company.nianglin.service.support.metrics;

import org.company.nianglin.common.DateRange;
import org.company.nianglin.config.OperationsProperties;
import org.company.nianglin.mapper.OperationsMapper;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 履约率计算器（V3.6 · T3）。
 *
 * <h3>公式</h3>
 *
 * <p>（COMPLETED + REVIEWED）÷（COMPLETED + REVIEWED + CANCELLED），
 * 与指标字典 {@code operations.fulfillment_rate} 逐字同源，
 * 也与 V3.5 驾驶舱「概览 / 健康度」用的是<b>同一份聚合</b>
 * （{@code OperationsMapper#selectOrderStats}）。</p>
 *
 * <h3>为什么它同时挂着陪诊员端的 key</h3>
 *
 * <p>指标字典里 {@code companion.workbench.completion_rate}（陪诊员区间完成率）
 * 的公式与本指标<b>逐字相同</b>，只是出口不同。决策 6.2 要求「同一个口径」，
 * 所以这里只写一份实现，两个 key 都指向它 ——
 * 两处各写一遍公式，迟早会漂移，那正是 V3.2 在用药三桶上踩过的坑（ADR-0014）。</p>
 *
 * <p>⚠️ 口径说明：本计算器是<b>平台级</b>口径（管理端视角）。
 * 陪诊员个人口径仍由 V3.3 的 {@code CompanionStatisticsServiceImpl}
 * 按陪诊员过滤后计算，两者公式同源、范围不同。</p>
 *
 * @author 银龄伴诊团队
 * @since V3.6
 */
@Component
public class FulfillmentRateCalculator extends AbstractMetricCalculator {

    public FulfillmentRateCalculator(OperationsMapper operationsMapper, OperationsProperties operationsProperties) {
        super(operationsMapper, operationsProperties);
    }

    @Override
    public String metricKey() {
        return "operations.fulfillment_rate";
    }

    @Override
    public List<String> aliasKeys() {
        return List.of("companion.workbench.completion_rate");
    }

    @Override
    public String calculate(DateRange range) {
        Map<String, Object> stats = orderStats(range);
        long completed = longOf(stats, "completedCount");
        long cancelled = longOf(stats, "cancelledCount");
        return percent(completed, completed + cancelled);
    }
}

package org.company.nianglin.service.support.metrics;

import org.company.nianglin.common.DateRange;
import org.company.nianglin.config.OperationsProperties;
import org.company.nianglin.mapper.OperationsMapper;
import org.springframework.stereotype.Component;


/**
 * 平均评分计算器（V3.6 · T3）。
 *
 * <h3>公式</h3>
 *
 * <p>区间内<b>有效评价</b>（{@code is_valid = 1}）的平均分。
 * 管理员判定无效的评价不参与聚合 —— 否则「洗白一条差评」反而会把平均分拉高。</p>
 *
 * <p>渲染成两位小数字符串（不带 {@code %}）：评分不是比率，
 * 给它加个百分号会让人误以为是「好评率 4.50%」。</p>
 *
 * @author 银龄伴诊团队
 * @since V3.6
 */
@Component
public class AverageScoreCalculator extends AbstractMetricCalculator {

    public AverageScoreCalculator(OperationsMapper operationsMapper, OperationsProperties operationsProperties) {
        super(operationsMapper, operationsProperties);
    }

    @Override
    public String metricKey() {
        return "operations.average_score";
    }

    @Override
    public String calculate(DateRange range) {
        // 公式本体在 MetricFormulas —— 驾驶舱概览 / 健康度调的是同一份实现
        return MetricFormulas.averageScore(reviewStats(range));
    }
}

package org.company.nianglin.service.support.metrics;

import org.company.nianglin.common.DateRange;
import org.company.nianglin.config.OperationsProperties;
import org.company.nianglin.mapper.OperationsMapper;
import org.springframework.stereotype.Component;


/**
 * 投诉率计算器（V3.6 · T3）。
 *
 * <h3>公式</h3>
 *
 * <p>投诉<b>订单</b>数 ÷ 区间订单数。分子是 {@code COUNT(DISTINCT complaint.order_id)}
 * —— 同一笔订单被投诉两次只算一单，否则平台可以靠「重复投诉同一笔单」把投诉率刷上去，
 * 指标就失去了度量服务质量的含义。</p>
 *
 * @author 银龄伴诊团队
 * @since V3.6
 */
@Component
public class ComplaintRateCalculator extends AbstractMetricCalculator {

    public ComplaintRateCalculator(OperationsMapper operationsMapper, OperationsProperties operationsProperties) {
        super(operationsMapper, operationsProperties);
    }

    @Override
    public String metricKey() {
        return "operations.complaint_rate";
    }

    @Override
    public String calculate(DateRange range) {
        // 公式本体在 MetricFormulas —— 驾驶舱概览 / 健康度调的是同一份实现
        return MetricFormulas.complaintRate(orderStats(range), complaintStats(range));
    }
}

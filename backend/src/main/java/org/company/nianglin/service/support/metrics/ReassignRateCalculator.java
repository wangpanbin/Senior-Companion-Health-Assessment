package org.company.nianglin.service.support.metrics;

import org.company.nianglin.common.DateRange;
import org.company.nianglin.config.OperationsProperties;
import org.company.nianglin.mapper.OperationsMapper;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 改派率计算器（V3.6 · T3 · T6 补登）。
 *
 * <h3>公式</h3>
 *
 * <p>被管理员改派的订单数 ÷ 区间订单数。分子取 {@code admin_oper_log} 里
 * {@code oper_type = 'REASSIGN_ORDER'} 的记录并<b>按订单去重</b>
 * （同一单被改派多次只算一单）。</p>
 *
 * <p>⚠️ 分子按<b>操作日志时间</b>过滤、分母按<b>下单时间</b>过滤 ——
 * 订单表里没有「被改派过」的痕迹，改派这件事只发生在操作日志里。
 * 这条时间字段不同源的事实写进了指标字典的公式，不藏着。</p>
 *
 * @author 银龄伴诊团队
 * @since V3.6
 */
@Component
public class ReassignRateCalculator extends AbstractMetricCalculator {

    public ReassignRateCalculator(OperationsMapper operationsMapper, OperationsProperties operationsProperties) {
        super(operationsMapper, operationsProperties);
    }

    @Override
    public String metricKey() {
        return "operations.reassign_rate";
    }

    @Override
    public String calculate(DateRange range) {
        Map<String, Object> stats = orderStats(range);
        long total = longOf(stats, "totalCount");
        Integer reassigned = operationsMapper.selectReassignedOrderCount(range.startTime(), range.endTime());
        return percent(longOf(reassigned), total);
    }
}

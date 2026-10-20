package org.company.nianglin.service.support.matching;

import java.util.Map;

/**
 * 某个订单对某个陪诊员的匹配排名（V3.4 · FR-Service）。
 *
 * <p>大厅排序用：每个订单算一个匹配分，再按分排序。</p>
 *
 * @param orderId    订单 ID
 * @param total      加权总分（0.0 ~ 1.0）
 * @param dimensions 各维度得分（0.0 ~ 1.0）
 * @author 银龄伴诊团队
 * @since V3.4
 */
public record OrderMatchRank(Long orderId, double total, Map<String, Double> dimensions) {

    /** 某维度的得分；维度不存在返回 0 */
    public double dimension(String name) {
        return dimensions == null ? 0D : dimensions.getOrDefault(name, 0D);
    }
}

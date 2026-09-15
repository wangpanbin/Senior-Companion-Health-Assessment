package org.company.nianglin.constant;

import lombok.Getter;

/**
 * 陪诊员排行指标（{@code docs/api/09-statistics-export.md} §4）。
 *
 * <p>{@code metric} = {@code ORDER_COUNT}（默认）/ {@code SCORE}。</p>
 *
 * <p>两个指标在业务上回答的是不同问题：按接单数排是「谁最忙」，
 * 按评分排是「谁最受认可」。合并成一个「综合分」看起来更高级，
 * 但会让排行结果无法解释 —— 管理员问「为什么他排第一」时，
 * 综合分只能回答「因为算出来就是这样」。</p>
 *
 * @author 银龄伴诊团队
 * @since M10
 */
@Getter
public enum CompanionRankMetric {

    ORDER_COUNT("按接单数"),
    SCORE("按评分"),
    ;

    private final String label;

    CompanionRankMetric(String label) {
        this.label = label;
    }

    /** 按名称反查（忽略大小写），非法值返回 {@code null} */
    public static CompanionRankMetric of(String name) {
        if (name == null) {
            return null;
        }
        for (CompanionRankMetric m : values()) {
            if (m.name().equalsIgnoreCase(name)) {
                return m;
            }
        }
        return null;
    }
}

package org.company.nianglin.constant;

import lombok.Getter;

/**
 * 陪诊员工作台的趋势维度（V3.3 · FR-Trend）。
 *
 * <p>与 {@link CompanionRankMetric} 同范式：枚举承载「合法取值 + 中文名」，
 * 由 {@code of(name)} 反查，非法值返回 {@code null} 由调用方决定抛什么错 ——
 * 枚举本身不依赖 {@code exception} 包（AGENTS.md §2.2 的分层纪律）。</p>
 *
 * @author 银龄伴诊团队
 * @since V3.3
 */
@Getter
public enum CompanionTrendDimension {

    ORDER("完成订单"),
    INCOME("收入"),
    RATING("评分"),
    ;

    /** 中文展示名（同时用作图表图例） */
    private final String label;

    CompanionTrendDimension(String label) {
        this.label = label;
    }

    /** 按名称反查（忽略大小写），非法值返回 {@code null} */
    public static CompanionTrendDimension of(String name) {
        if (name == null) {
            return null;
        }
        for (CompanionTrendDimension d : values()) {
            if (d.name().equalsIgnoreCase(name)) {
                return d;
            }
        }
        return null;
    }
}

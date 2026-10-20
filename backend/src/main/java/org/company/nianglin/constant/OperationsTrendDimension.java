package org.company.nianglin.constant;

import lombok.Getter;

/**
 * 平台运营驾驶舱的趋势维度（V3.5 · T3）。
 *
 * <p>与 {@link CompanionTrendDimension} 同范式：枚举承载「合法取值 + 中文名」，
 * 由 {@code of(name)} 反查，非法值返回 {@code null} 由调用方决定抛什么错 ——
 * 枚举本身不依赖 {@code exception} 包（AGENTS.md §2.2 的分层纪律）。</p>
 *
 * @author 银龄伴诊团队
 * @since V3.5
 */
@Getter
public enum OperationsTrendDimension {

    /** 订单量（区间内新增订单数） */
    ORDER("订单量"),

    /** 活跃陪诊员数（当天接过单的陪诊员数） */
    COMPANION_ACTIVE("活跃陪诊员数"),

    /** 履约率（当天完成 ÷（完成 + 取消），百分比数值） */
    FULFILLMENT_RATE("履约率(%)"),

    /** 投诉订单数（当天被投诉的订单数） */
    COMPLAINT("投诉订单数"),

    /** 评价条数（当天的有效评价数） */
    REVIEW("评价条数"),

    /** 用药任务数（当天的服药任务数） */
    MEDICATION("用药任务数"),
    ;

    /** 中文展示名（同时用作图表图例） */
    private final String label;

    OperationsTrendDimension(String label) {
        this.label = label;
    }

    /** 按名称反查（忽略大小写），非法值返回 {@code null} */
    public static OperationsTrendDimension of(String name) {
        if (name == null) {
            return null;
        }
        for (OperationsTrendDimension d : values()) {
            if (d.name().equalsIgnoreCase(name)) {
                return d;
            }
        }
        return null;
    }
}

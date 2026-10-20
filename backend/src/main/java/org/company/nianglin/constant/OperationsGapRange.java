package org.company.nianglin.constant;

import lombok.Getter;

/**
 * 供给时段缺口分析的区间档位（V3.6 · T4）。
 *
 * <p>{@code LAST_7D} / {@code LAST_30D} 由服务端按「今天」推算，
 * {@code CUSTOM} 才用请求里的 {@code startDate} / {@code endDate}。</p>
 *
 * <h3>为什么档位只到「天」</h3>
 *
 * <p>桶的粒度是「周内第几天 × 小时」，与区间长度无关：
 * 回溯 7 天还是 30 天，都是 168 个桶。档位只决定<b>看多少天的订单</b>，
 * 不决定桶的形状 —— 否则「这一周比上一周缺口大」就没法直接比了。</p>
 *
 * <p>（与 {@link OperationsTrendRange} 分开而不是合并：趋势端的 {@code TODAY}
 * 对缺口分析没有意义 —— 一天的订单量不足以支撑「某时段供给不足」的判断。）</p>
 *
 * @author 银龄伴诊团队
 * @since V3.6
 */
@Getter
public enum OperationsGapRange {

    /** 最近 7 天（含今天） */
    LAST_7D("最近 7 天", 7),

    /** 最近 30 天（含今天） */
    LAST_30D("最近 30 天", 30),

    /** 自定义区间（用 startDate / endDate） */
    CUSTOM("自定义", 0),
    ;

    /** 中文展示名 */
    private final String label;

    /** 区间天数（{@link #CUSTOM} 为 0，表示由请求指定） */
    private final int days;

    OperationsGapRange(String label, int days) {
        this.label = label;
        this.days = days;
    }

    /** 按名称反查（忽略大小写），非法值返回 {@code null} */
    public static OperationsGapRange of(String name) {
        if (name == null) {
            return null;
        }
        for (OperationsGapRange r : values()) {
            if (r.name().equalsIgnoreCase(name)) {
                return r;
            }
        }
        return null;
    }
}

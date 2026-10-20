package org.company.nianglin.constant;

import lombok.Getter;

/**
 * 平台运营驾驶舱的趋势区间（V3.5 · T3）。
 *
 * <p>{@code TODAY} / {@code LAST_7D} / {@code LAST_30D} 由服务端按「今天」推算，
 * {@code CUSTOM} 才用请求里的 {@code startDate} / {@code endDate}。</p>
 *
 * <p>为什么不用「最近 N 天」的自由数字：区间长度会直接决定分类轴的点数，
 * 而驾驶舱的图例与刻度是按固定档位设计的 —— 自由数字会让同一张图在两次请求里
 * 长得完全不同。</p>
 *
 * @author 银龄伴诊团队
 * @since V3.5
 */
@Getter
public enum OperationsTrendRange {

    /** 今天（00:00 – 23:59:59） */
    TODAY("今天", 1),

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

    OperationsTrendRange(String label, int days) {
        this.label = label;
        this.days = days;
    }

    /** 按名称反查（忽略大小写），非法值返回 {@code null} */
    public static OperationsTrendRange of(String name) {
        if (name == null) {
            return null;
        }
        for (OperationsTrendRange r : values()) {
            if (r.name().equalsIgnoreCase(name)) {
                return r;
            }
        }
        return null;
    }
}

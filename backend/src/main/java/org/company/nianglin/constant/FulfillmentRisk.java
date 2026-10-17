package org.company.nianglin.constant;

import lombok.Getter;

/**
 * 订单履约风险等级（V3.1 · T1，{@code companion_order.fulfillment_risk}）。
 *
 * <h3>它<b>不是</b>订单状态</h3>
 *
 * <p>订单状态机（见 {@link OrderStatus}）回答的是「这笔单走到哪一步了」，
 * 履约风险回答的是「这笔单有没有可能接不成」。两者是<b>正交</b>的：</p>
 *
 * <ul>
 *   <li>一笔订单可以是 {@link OrderStatus#PENDING} + {@link #WARNING}（还在等人接，但等太久了）；</li>
 *   <li>一笔订单可以是 {@link OrderStatus#ACCEPTED} + {@link #NORMAL}（人接了，风险自动消失）。</li>
 * </ul>
 *
 * <p><b>二者永不互相推导。</b>把风险做成新状态（例如加一个 {@code AT_RISK}）会同时破坏三件事：
 * 订单时间线会被风险跳级污染、状态机转移表要重写、{@code OrderStatusTest} 的完备性判据失效。
 * 因此风险是一列可打标的字段，改动它<b>不经过</b>状态流转唯一入口。</p>
 *
 * <h3>只在 PENDING 时有意义</h3>
 *
 * <p>离开 {@code PENDING}（被接单 / 被取消）时风险一律回落 {@link #NORMAL} 并停止扫描。
 * 已接单的订单再说「等待风险高」是没有意义的。</p>
 *
 * @author 银龄伴诊团队
 * @since V3.1
 */
@Getter
public enum FulfillmentRisk {

    /** 正常等待 */
    NORMAL("正常等待", 1),

    /** 等待时间较长，系统正在扩大匹配范围 */
    WARNING("等待时间较长", 2),

    /** 履约风险较高，允许家属催单、需平台关注 */
    URGENT("履约风险较高", 3),

    /** 需要管理员人工介入 */
    MANUAL_REQUIRED("需要人工介入", 4),
    ;

    private final String label;

    /**
     * 严重度权重，1 最低。
     *
     * <p>用于比较与排序，<b>不要</b>用它做阈值判断（阈值由配置项决定）。
     * 把它放在枚举里而不是散落成魔法数字，是为了让「谁比谁严重」这件事
     * 在代码里只有一个真相来源。</p>
     */
    private final int severity;

    FulfillmentRisk(String label, int severity) {
        this.label = label;
        this.severity = severity;
    }

    /** 是否已达到或超过给定严重度 */
    public boolean atLeast(FulfillmentRisk other) {
        return other != null && this.severity >= other.severity;
    }

    /** 是否属于「风险已经升高」的等级（不含 NORMAL） */
    public boolean isRaised() {
        return this != NORMAL;
    }

    public static FulfillmentRisk of(String name) {
        if (name == null) {
            return null;
        }
        for (FulfillmentRisk r : values()) {
            if (r.name().equalsIgnoreCase(name)) {
                return r;
            }
        }
        return null;
    }

    /** 中文展示名；入参为空或非法时原样返回（与 OrderStatus.labelOf 同风格） */
    public static String labelOf(String name) {
        FulfillmentRisk r = of(name);
        return r == null ? name : r.getLabel();
    }
}

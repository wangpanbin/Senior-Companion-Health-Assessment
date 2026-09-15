package org.company.nianglin.constant;

import lombok.Getter;

/**
 * 订单结算状态（{@code companion_order.payment_status}）。
 *
 * <p><b>一期不做在线支付</b>（plan.md 合规与范围约定）：平台只做「线上记账 + 线下结算」，
 * 因此本枚举描述的是<b>记账状态</b>，不是支付渠道状态 —— 钱怎么付、付没付，
 * 由家属与陪诊员线下解决，平台只记录「结算了没有」。</p>
 *
 * <p>这意味着两件事，写代码时不要搞错：</p>
 * <ol>
 *   <li>订单完成时不会自动变成 {@link #SETTLED}；
 *       {@code COMPLETED} 与 {@code UNPAID} 同时存在是<b>正常状态</b>，不是 bug。</li>
 *   <li>没有任何接口会因为 {@link #UNPAID} 而阻止后续流程（如评价）——
 *       平台不碰钱，就不该拿钱当流程闸门。</li>
 * </ol>
 *
 * <p>状态置为 {@link #SETTLED} 的入口在 M9/M10（管理员对账或线下结算回填）。</p>
 *
 * @author 银龄伴诊团队
 * @since M4
 */
@Getter
public enum PaymentStatus {

    UNPAID("未结算"),
    SETTLED("已结算"),
    ;

    private final String label;

    PaymentStatus(String label) {
        this.label = label;
    }

    /** 按枚举名反查（忽略大小写），非法值返回 {@code null} 由调用方决定抛什么错 */
    public static PaymentStatus of(String name) {
        if (name == null) {
            return null;
        }
        for (PaymentStatus s : values()) {
            if (s.name().equalsIgnoreCase(name)) {
                return s;
            }
        }
        return null;
    }

    /** 中文展示名；入参为空或非法时原样返回，避免 VO 里出现 {@code null} */
    public static String labelOf(String name) {
        PaymentStatus s = of(name);
        return s == null ? name : s.getLabel();
    }
}

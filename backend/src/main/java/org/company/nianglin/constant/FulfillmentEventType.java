package org.company.nianglin.constant;

import lombok.Getter;

/**
 * 订单履约事件类型（V3.1 · T1，{@code order_fulfillment_event.event_type}）。
 *
 * <p>与 {@code order_status_log} 的区别：那张表记的是「订单状态从 X 变到 Y」，
 * 这张表记的是「履约过程中发生了什么」。风险升级<b>不改</b>订单状态，
 * 所以它的事件必须落在本枚举对应的表里，不能混进状态流转日志。</p>
 *
 * @author 银龄伴诊团队
 * @since V3.1
 */
@Getter
public enum FulfillmentEventType {

    /** 订单创建（家属下单落库瞬间） */
    ORDER_CREATED("订单创建"),

    /** 开始在大厅匹配 */
    MATCHING_STARTED("开始匹配"),

    /** 风险升为「等待时间较长」 */
    FULFILLMENT_WARNING("等待时间较长"),

    /** 家属催单 */
    USER_URGED("家属催促接单"),

    /** 风险升为「履约风险较高」 */
    FULFILLMENT_URGENT("履约风险升高"),

    /** 风险升为「需要人工介入」 */
    MANUAL_REQUIRED("需要人工介入"),

    /** 管理员已人工协调 */
    ADMIN_COORDINATED("管理员已协调"),

    /** 管理员改派陪诊员 */
    ORDER_REASSIGNED("订单改派"),
    ;

    private final String label;

    FulfillmentEventType(String label) {
        this.label = label;
    }

    /** 是否为「风险等级发生变化」的事件；只有这些事件需要填 from_risk / to_risk */
    public boolean isRiskChange() {
        return this == FULFILLMENT_WARNING
                || this == FULFILLMENT_URGENT
                || this == MANUAL_REQUIRED;
    }

    public static String labelOf(String name) {
        if (name == null) {
            return null;
        }
        for (FulfillmentEventType t : values()) {
            if (t.name().equalsIgnoreCase(name)) {
                return t.getLabel();
            }
        }
        return name;
    }
}

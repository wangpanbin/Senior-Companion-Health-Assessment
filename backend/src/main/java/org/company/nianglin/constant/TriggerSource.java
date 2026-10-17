package org.company.nianglin.constant;

import lombok.Getter;

/**
 * 履约事件触发来源（V3.1 · T1，{@code order_fulfillment_event.trigger_source}）。
 *
 * <p>它回答的是「谁让这件事发生的」，与 {@code operator_role}（这个人的角色）
 * 正交：系统自动扫描触发的升级，{@code operator_role} 为 {@code SYSTEM}、
 * {@code operator_id} 为 {@code null}，但触发来源仍然是 {@code SYSTEM}。</p>
 *
 * @author 银龄伴诊团队
 * @since V3.1
 */
@Getter
public enum TriggerSource {

    /** 用户操作（家属催单） */
    USER("用户操作"),

    /** 管理员操作（人工协调、改派） */
    ADMIN("管理员操作"),

    /** 系统自动（定时扫描、订单创建时写入） */
    SYSTEM("系统自动"),
    ;

    private final String label;

    TriggerSource(String label) {
        this.label = label;
    }
}

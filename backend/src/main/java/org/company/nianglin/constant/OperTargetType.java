package org.company.nianglin.constant;

import lombok.Getter;

/**
 * 管理员操作的目标类型（{@code admin_oper_log.target_type}）。
 *
 * <p>对应 {@code docs/api/08-admin.md} §一 {@code OperLogVO.targetType}：
 * {@code USER} / {@code ORDER} / {@code COMPANION} / {@code COMPLAINT}。</p>
 *
 * <h3>为什么和 {@code OperType} 分开</h3>
 *
 * <p>「做了什么」与「对谁做」是两个独立维度：封禁一个用户是
 * {@code DISABLE_USER + USER}，处理一条投诉是 {@code HANDLE_COMPLAINT + COMPLAINT}。
 * 合并成一个枚举会逼出 {@code DISABLE_COMPLAINT} 这种无意义取值，
 * 也会让「查某人被操作过几次」这种查询无法只用一个字段表达。</p>
 *
 * @author 银龄伴诊团队
 * @since M9
 */
@Getter
public enum OperTargetType {

    USER("用户"),
    ORDER("订单"),
    COMPANION("陪诊员"),
    COMPLAINT("投诉"),
    ;

    private final String label;

    OperTargetType(String label) {
        this.label = label;
    }

    /** 按名称反查（忽略大小写），非法值返回 {@code null} */
    public static OperTargetType of(String name) {
        if (name == null) {
            return null;
        }
        for (OperTargetType type : values()) {
            if (type.name().equalsIgnoreCase(name)) {
                return type;
            }
        }
        return null;
    }
}

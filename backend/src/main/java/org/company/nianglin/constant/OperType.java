package org.company.nianglin.constant;

import lombok.Getter;

/**
 * 管理员操作类型（{@code admin_oper_log.oper_type}）。
 *
 * <p>对应 {@code docs/api/08-admin.md} §一「操作类型枚举」。</p>
 *
 * <h3>为什么枚举要覆盖"读"以外的每个动作</h3>
 *
 * <p>操作日志的价值在于事后追责：出问题时能回答「谁在什么时候把这个人封了」。
 * 只要有一个写动作没落日志，那次操作就变成了无法追溯的空白 ——
 * 而恰恰是「不该做的操作」最容易被漏记。因此这里的取值集合
 * <b>与本模块所有写接口一一对应</b>，新增管理端写接口必须同时新增枚举值。</p>
 *
 * <p>注意 {@code PUBLISH_NOTICE}（发布系统公告）一期只保留枚举：
 * 公告走 M8 的站内信群发，没有独立的管理端接口，等需求明确后再落接口。</p>
 *
 * @author 银龄伴诊团队
 * @since M9
 */
@Getter
public enum OperType {

    AUDIT_COMPANION("审核陪诊员资质"),
    DISABLE_USER("封禁用户"),
    ENABLE_USER("解封用户"),
    RESET_PASSWORD("重置密码"),
    ARBITRATE_ORDER("订单纠纷处理"),
    HANDLE_COMPLAINT("处理投诉"),
    PUBLISH_NOTICE("发布系统公告"),
    ;

    private final String label;

    OperType(String label) {
        this.label = label;
    }

    /** 按名称反查（忽略大小写），非法值返回 {@code null} */
    public static OperType of(String name) {
        if (name == null) {
            return null;
        }
        for (OperType type : values()) {
            if (type.name().equalsIgnoreCase(name)) {
                return type;
            }
        }
        return null;
    }

    /** 中文展示名；非法值原样返回，避免日志页因为一条脏数据整体 500 */
    public static String labelOf(String name) {
        OperType type = of(name);
        return type == null ? name : type.getLabel();
    }
}

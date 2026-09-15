package org.company.nianglin.constant;

import lombok.Getter;

/**
 * 资质审核状态。
 *
 * <p>用于 {@code companion_profile.audit_status} 与 {@code companion_audit_record.audit_status}。
 * 两条业务规则由本枚举承载：</p>
 * <ol>
 *   <li><b>只有 {@link #APPROVED} 才允许接单</b>（M4 订单模块据此拦截，返回 2003）；</li>
 *   <li>{@link #REJECTED} 必须携带驳回原因，否则管理员接口返回 8003。</li>
 * </ol>
 *
 * @author 银龄伴诊团队
 * @since M3
 */
@Getter
public enum AuditStatus {

    PENDING("待审核"),
    APPROVED("已通过"),
    REJECTED("已驳回"),
    ;

    private final String label;

    AuditStatus(String label) {
        this.label = label;
    }

    /** 按枚举名反查（忽略大小写），非法值返回 {@code null} 由调用方决定抛什么错 */
    public static AuditStatus of(String name) {
        if (name == null) {
            return null;
        }
        for (AuditStatus s : values()) {
            if (s.name().equalsIgnoreCase(name)) {
                return s;
            }
        }
        return null;
    }

    /** 是否已通过审核（唯一允许接单的状态） */
    public boolean isApproved() {
        return this == APPROVED;
    }

    /**
     * 中文展示名，入参为空时返回 {@code null}。
     *
     * <p>VO 装配用，避免每个 VO 里重复写 {@code of(...) == null ? ... : ...}。</p>
     */
    public static String labelOf(String name) {
        AuditStatus s = of(name);
        return s == null ? name : s.getLabel();
    }
}

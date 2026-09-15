package org.company.nianglin.constant;

import lombok.Getter;

/**
 * 老人档案的家属绑定状态（{@code elder_profile.bind_status}）。
 *
 * <p>语义是「<b>这个老人当前有没有被家属管理</b>」，不是「有没有登录账号」。
 * 家属代建的、还没关联登录账号的档案（{@code user_id IS NULL}）同样可以是 {@link #BOUND}。</p>
 *
 * @author 银龄伴诊团队
 * @since M3
 */
@Getter
public enum BindStatus {

    BOUND("已绑定"),
    UNBOUND("未绑定"),
    ;

    private final String label;

    BindStatus(String label) {
        this.label = label;
    }

    public static BindStatus of(String name) {
        if (name == null) {
            return null;
        }
        for (BindStatus s : values()) {
            if (s.name().equalsIgnoreCase(name)) {
                return s;
            }
        }
        return null;
    }
}

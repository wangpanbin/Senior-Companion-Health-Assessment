package org.company.nianglin.constant;

import lombok.Getter;

/**
 * 陪诊员接单状态（{@code companion_profile.work_status}）。
 *
 * <p>与资质 {@link AuditStatus} 是<b>两个正交的开关</b>：
 * 资质决定「有没有资格接单」，接单状态决定「现在想不想接单」。
 * 一个已通过的陪诊员可以把自己置为 {@link #REST} 去休息，
 * 但他不会因为休息而丢掉资质；反过来，待审核的人即使把状态设成
 * {@link #AVAILABLE} 也接不了单 —— 判定顺序永远是「先看资质，再看状态」。</p>
 *
 * @author 银龄伴诊团队
 * @since M3
 */
@Getter
public enum WorkStatus {

    AVAILABLE("可接单"),
    REST("休息中"),
    ;

    private final String label;

    WorkStatus(String label) {
        this.label = label;
    }

    public static WorkStatus of(String name) {
        if (name == null) {
            return null;
        }
        for (WorkStatus s : values()) {
            if (s.name().equalsIgnoreCase(name)) {
                return s;
            }
        }
        return null;
    }

    public static String labelOf(String name) {
        WorkStatus s = of(name);
        return s == null ? name : s.getLabel();
    }
}

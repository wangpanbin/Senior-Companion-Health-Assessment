package org.company.nianglin.constant;

import lombok.Getter;

/**
 * 用药计划状态（{@code medication_plan.status}）。
 *
 * <p><b>停用不是删除。</b> 计划一旦停用，历史服药记录必须完整保留 ——
 * 「9 月 1 日到 9 月 30 日吃过哪些药」是医生复诊时会问的问题，
 * 物理删除会让这段时间的服药历史凭空消失。
 * 因此 {@link #DISABLED} 只是「不再生成新任务」，不是「把过去抹掉」。</p>
 *
 * @author 银龄伴诊团队
 * @since M6
 */
@Getter
public enum MedicationPlanStatus {

    ACTIVE("进行中"),
    DISABLED("已停用"),
    ;

    private final String label;

    MedicationPlanStatus(String label) {
        this.label = label;
    }

    /** 按名称反查（忽略大小写），非法值返回 {@code null} */
    public static MedicationPlanStatus of(String name) {
        if (name == null) {
            return null;
        }
        for (MedicationPlanStatus s : values()) {
            if (s.name().equalsIgnoreCase(name) || s.label.equals(name)) {
                return s;
            }
        }
        return null;
    }

    /** 中文展示名；非法值原样返回 */
    public static String labelOf(String name) {
        MedicationPlanStatus s = of(name);
        return s == null ? name : s.getLabel();
    }
}

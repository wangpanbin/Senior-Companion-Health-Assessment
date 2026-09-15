package org.company.nianglin.constant;

import lombok.Getter;

/**
 * 用药与饭点的关系（{@code medication_plan.meal_relation}）。
 *
 * <p>取值只有三个，且必须原样存最小集合 —— 不要为了「早上饭前 / 晚上饭后」
 * 这种组合再拆枚举。饭点本身在不同地区差异极大，系统一旦开始解释
 * 「几点算早饭」，就已经越过了「只做记录」的边界。</p>
 *
 * @author 银龄伴诊团队
 * @since M6
 */
@Getter
public enum MealRelation {

    BEFORE_MEAL("饭前"),
    AFTER_MEAL("饭后"),
    ANY("不限"),
    ;

    private final String label;

    MealRelation(String label) {
        this.label = label;
    }

    /** 按名称反查（忽略大小写），非法值返回 {@code null} */
    public static MealRelation of(String name) {
        if (name == null) {
            return null;
        }
        for (MealRelation r : values()) {
            if (r.name().equalsIgnoreCase(name) || r.label.equals(name)) {
                return r;
            }
        }
        return null;
    }

    /** 中文展示名；非法值原样返回 */
    public static String labelOf(String name) {
        MealRelation r = of(name);
        return r == null ? name : r.getLabel();
    }
}

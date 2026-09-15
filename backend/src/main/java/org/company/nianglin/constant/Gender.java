package org.company.nianglin.constant;

import lombok.Getter;

/**
 * 性别（{@code elder_profile.gender} / {@code companion_profile.gender}）。
 *
 * @author 银龄伴诊团队
 * @since M3
 */
@Getter
public enum Gender {

    MALE("男"),
    FEMALE("女"),
    ;

    private final String label;

    Gender(String label) {
        this.label = label;
    }

    public static Gender of(String name) {
        if (name == null) {
            return null;
        }
        for (Gender g : values()) {
            if (g.name().equalsIgnoreCase(name)) {
                return g;
            }
        }
        return null;
    }

    /**
     * 中文展示名。
     *
     * <p>数据库里是 NOT NULL 列，理论上不会为空；真为空说明有脏数据，
     * 此时返回原始值而不是中文，便于排查。</p>
     */
    public static String labelOf(String name) {
        Gender g = of(name);
        return g == null ? name : g.getLabel();
    }
}

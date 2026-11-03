package org.company.nianglin.constant;

import java.util.Arrays;

/**
 * 体征录入来源（V4 · P2-2）。
 *
 * <p>来源只回答「这条数值是谁录的」，与数值本身无关。
 * 老人账号为只读模式，因此不存在 ELDER 来源。</p>
 *
 * @author 银龄伴诊团队
 * @since V4 · P2-2
 */
public enum VitalSource {

    /** 家属代录 */
    FAMILY_MANUAL("家属代录"),

    /** 陪诊员服务时代录 */
    COMPANION_SERVICE("陪诊员服务代录");

    /** 中文展示名 */
    private final String label;

    VitalSource(String label) {
        this.label = label;
    }

    /** 中文展示名 */
    public String getLabel() {
        return label;
    }

    /**
     * 按枚举名反查（忽略大小写与首尾空白），非法值返回 {@code null}。
     *
     * @param name 枚举名字面量，可为 {@code null}
     * @return 来源；非法值返回 {@code null}
     */
    public static VitalSource of(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        return Arrays.stream(values())
                .filter(source -> source.name().equalsIgnoreCase(name.trim()))
                .findFirst()
                .orElse(null);
    }

    /** 中文展示名；非法值返回 {@code null} */
    public static String labelOf(String name) {
        VitalSource source = of(name);
        return source == null ? null : source.label;
    }
}

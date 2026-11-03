package org.company.nianglin.constant;

import java.util.Arrays;

/**
 * 血糖测量场景（V4 · P2-2）。
 *
 * <p>场景是<b>测量时的进食状态</b>，属于事实描述，不是对数值的判断。
 * 仅 {@code BLOOD_GLUCOSE} 类型需要该字段。</p>
 *
 * @author 银龄伴诊团队
 * @since V4 · P2-2
 */
public enum GlucoseContext {

    /** 空腹 */
    FASTING("空腹"),

    /** 餐后 2 小时 */
    POSTPRANDIAL("餐后 2 小时"),

    /** 随机 */
    RANDOM("随机");

    /** 中文展示名 */
    private final String label;

    GlucoseContext(String label) {
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
     * @return 场景；非法值返回 {@code null}
     */
    public static GlucoseContext of(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        return Arrays.stream(values())
                .filter(context -> context.name().equalsIgnoreCase(name.trim()))
                .findFirst()
                .orElse(null);
    }

    /** 中文展示名；非法值返回 {@code null} */
    public static String labelOf(String name) {
        GlucoseContext context = of(name);
        return context == null ? null : context.label;
    }
}

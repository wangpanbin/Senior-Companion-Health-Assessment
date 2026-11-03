package org.company.nianglin.constant;

import java.util.Arrays;

/**
 * 体征类型（V4 · P2-2）。
 *
 * <p>本枚举只回答「这条记录是哪一类测量」，<b>不携带任何区间、目标值或分级</b>
 * （合规红线-1：只记录与展示数值，不做任何解释）。单位用于服务端拼装展示文本，
 * 前端不重复拼字符串。</p>
 *
 * @author 银龄伴诊团队
 * @since V4 · P2-2
 */
public enum VitalType {

    /** 血压：收缩压 / 舒张压 */
    BLOOD_PRESSURE("血压", "mmHg"),

    /** 血糖：数值 + 测量场景 */
    BLOOD_GLUCOSE("血糖", "mmol/L"),

    /** 体重 */
    WEIGHT("体重", "kg");

    /** 中文展示名 */
    private final String label;

    /** 展示单位 */
    private final String unit;

    VitalType(String label, String unit) {
        this.label = label;
        this.unit = unit;
    }

    /** 中文展示名 */
    public String getLabel() {
        return label;
    }

    /** 展示单位 */
    public String getUnit() {
        return unit;
    }

    /**
     * 按枚举名反查（忽略大小写与首尾空白），非法值返回 {@code null} 由调用方决定抛什么错。
     *
     * @param name 枚举名字面量，可为 {@code null}
     * @return 类型；非法值返回 {@code null}
     */
    public static VitalType of(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        return Arrays.stream(values())
                .filter(type -> type.name().equalsIgnoreCase(name.trim()))
                .findFirst()
                .orElse(null);
    }

    /** 中文展示名；非法值返回 {@code null}（不抛异常，展示层自行兜底） */
    public static String labelOf(String name) {
        VitalType type = of(name);
        return type == null ? null : type.label;
    }

    /** 展示单位；非法值返回 {@code null} */
    public static String unitOf(String name) {
        VitalType type = of(name);
        return type == null ? null : type.unit;
    }
}

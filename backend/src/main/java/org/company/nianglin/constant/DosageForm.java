package org.company.nianglin.constant;

/**
 * 剂型（{@code medicine_dict.dosage_form}）。
 *
 * <p>只用于筛选与展示。剂型是药品的客观属性，与「怎么吃」无关 ——
 * 本枚举刻意不提供任何按剂型推导用法的能力，
 * 因为那正是「系统在给用药建议」的起点。</p>
 *
 * @author 银龄伴诊团队
 * @since M6
 */
public enum DosageForm {

    TABLET("片剂"),
    CAPSULE("胶囊"),
    INJECTION("注射剂"),
    LIQUID("口服液"),
    OTHER("其他"),
    ;

    private final String label;

    DosageForm(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    /** 按名称反查（忽略大小写），非法值返回 {@code null} */
    public static DosageForm of(String name) {
        if (name == null) {
            return null;
        }
        for (DosageForm f : values()) {
            if (f.name().equalsIgnoreCase(name) || f.label.equals(name)) {
                return f;
            }
        }
        return null;
    }

    /** 中文展示名；非法值原样返回 */
    public static String labelOf(String name) {
        DosageForm f = of(name);
        return f == null ? name : f.getLabel();
    }
}

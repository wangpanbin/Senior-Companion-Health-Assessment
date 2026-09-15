package org.company.nianglin.constant;

import lombok.Getter;

/**
 * 投诉类型（{@code complaint.type}）。
 *
 * <p>对应 {@code docs/api/06-review-complaint.md} §一「投诉类型」表。</p>
 *
 * <p>枚举取值与文档一一对应，注释里的中文与文档保持完全一致 ——
 * 这张表会被管理员后台直接渲染成下拉框与筛选项，
 * 代码里的中文与文档里的中文不一致时，报障截图会对不上号。</p>
 *
 * @author 银龄伴诊团队
 * @since M7
 */
@Getter
public enum ComplaintType {

    LATE("迟到 / 未按时到达"),
    ATTITUDE("服务态度差"),
    INCOMPLETE("服务未完成"),
    FEE_DISPUTE("费用纠纷"),
    PRIVACY("隐私泄露"),
    OTHER("其他"),
    ;

    private final String label;

    ComplaintType(String label) {
        this.label = label;
    }

    /** 按名称反查（忽略大小写），非法值返回 {@code null} */
    public static ComplaintType of(String name) {
        if (name == null) {
            return null;
        }
        for (ComplaintType t : values()) {
            if (t.name().equalsIgnoreCase(name)) {
                return t;
            }
        }
        return null;
    }

    /** 中文展示名；非法值原样返回，避免列表因为一条脏数据整体 500 */
    public static String labelOf(String name) {
        ComplaintType t = of(name);
        return t == null ? name : t.getLabel();
    }
}

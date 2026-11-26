package org.company.nianglin.constant;

import lombok.Getter;

/**
 * 产品反馈分类（{@code product_feedback.type}）。
 *
 * <p>对应 {@code docs/api/15-product-feedback.md} §一「分类」表。</p>
 *
 * <h3>为什么入枚举而不是 sys_dict</h3>
 *
 * <p>与 {@link ComplaintType} 同款做法：分类会被管理端直接渲染成下拉框与筛选项，
 * 代码里的中文与文档里的中文不一致时，报障截图就对不上号。而分类一旦改动
 * 就要同步数据库里的历史取值 —— 放进可在线编辑的字典表反而让人不敢动它。</p>
 *
 * <h3>为什么只有四类</h3>
 *
 * <p>四类是「用户能自己判断」的上限：他分不清「缺陷」与「体验问题」的边界时，
 * 会全部落到 {@link #OTHER}，分类统计就失去意义。真正需要细分的维度
 * （页面 / 端 / 版本）我们**刻意不采集** —— 那是客户端指纹，与隐私最小化红线冲突。</p>
 *
 * @author 银龄伴诊团队
 * @since V6 · 产品反馈
 */
@Getter
public enum FeedbackType {

    /** 功能建议：希望新增某种能力 */
    FEATURE("功能建议"),
    /** 使用体验：功能存在但不好用 */
    EXPERIENCE("使用体验"),
    /** 缺陷问题：功能异常 / 报错 / 卡住 */
    DEFECT("缺陷问题"),
    /** 其他：归不进上面三类 */
    OTHER("其他"),
    ;

    private final String label;

    FeedbackType(String label) {
        this.label = label;
    }

    /** 按名称反查（忽略大小写），非法值返回 {@code null} */
    public static FeedbackType of(String name) {
        if (name == null) {
            return null;
        }
        for (FeedbackType t : values()) {
            if (t.name().equalsIgnoreCase(name)) {
                return t;
            }
        }
        return null;
    }

    /** 中文展示名；非法值原样返回，避免列表因为一条脏数据整体 500 */
    public static String labelOf(String name) {
        FeedbackType t = of(name);
        return t == null ? name : t.getLabel();
    }
}

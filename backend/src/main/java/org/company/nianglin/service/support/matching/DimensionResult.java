package org.company.nianglin.service.support.matching;

/**
 * 单个维度的评估结果（V3.4 · FR-Rule）。
 *
 * <h3>三段式：分 + 理由 + 降级说明</h3>
 *
 * <p>只有分数是不够的 —— 「匹配分 86」这个数字对家属毫无意义，
 * 「同服务区域 · 就诊时间在可服务时段内 · 服务过该科室 12 单」才有意义。</p>
 *
 * <p>而 {@code note} 记录的是<b>因为数据缺失而降级</b>的情况
 * （「未填写坐标，距离维度记 0」）。这两者必须分开：
 * 把降级写进 {@code reason} 会让人以为「距离不匹配」，
 * 而真相是「距离没法算」。</p>
 *
 * @param score  维度得分，{@code 0.0 ~ 1.0}
 * @param reason 命中理由（一句话，面向人）
 * @param note   降级说明；没有则 {@code null}
 * @author 银龄伴诊团队
 * @since V3.4
 */
public record DimensionResult(double score, String reason, String note) {

    /** 正常命中：无降级说明 */
    public static DimensionResult of(double score, String reason) {
        return new DimensionResult(score, reason, null);
    }

    /** 因数据缺失降级：必须写明原因 */
    public static DimensionResult degraded(double score, String reason, String note) {
        return new DimensionResult(score, reason, note);
    }

    /** 是否因数据缺失而降级 */
    public boolean isDegraded() {
        return note != null && !note.isBlank();
    }
}

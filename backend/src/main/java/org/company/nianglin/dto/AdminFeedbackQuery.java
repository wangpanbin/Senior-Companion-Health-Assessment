package org.company.nianglin.dto;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.company.nianglin.common.PageQuery;
import org.company.nianglin.entity.ProductFeedback;

/**
 * 管理端反馈列表查询入参（{@code docs/api/15-product-feedback.md} §4）。
 *
 * <p>与 {@link FeedbackQuery} 的差别：这里<b>不加收窄条件</b>，管理员看得到全部反馈。
 * 两个 DTO 刻意分开存在，而不是给同一个类加个「是否管理员」开关 ——
 * 复用会让「客户端必须限定提交人」这条安全约束与「管理端可以全看」的矛盾
 * 藏在同一个类里，改动时极容易改错方向（与 {@code AdminComplaintQuery} 同一取舍）。</p>
 *
 * @author 银龄伴诊团队
 * @since V6 · 产品反馈
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "反馈列表查询入参（管理端）")
public class AdminFeedbackQuery extends PageQuery<ProductFeedback> {

    @Schema(description = "处理状态：PENDING / PROCESSING / RESOLVED / REJECTED", example = "PENDING")
    private String status;

    @Schema(description = "分类：FEATURE / EXPERIENCE / DEFECT / OTHER", example = "EXPERIENCE")
    private String type;

    @Schema(description = "反馈正文 / 提交人姓名模糊搜索", example = "字号")
    private String keyword;

    @Schema(description = "提交时间起（yyyy-MM-dd）", example = "2026-11-01")
    private String startDate;

    @Schema(description = "提交时间止（yyyy-MM-dd，含当天）", example = "2026-11-30")
    private String endDate;

    /** MyBatis-Plus 分页对象 */
    public Page<ProductFeedback> toMpPage() {
        return toPage(new Page<>());
    }
}

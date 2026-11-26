package org.company.nianglin.dto;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.company.nianglin.common.PageQuery;
import org.company.nianglin.entity.ProductFeedback;

/**
 * 我的反馈列表查询入参（{@code docs/api/15-product-feedback.md} §2）。
 *
 * <p>这里<b>没有</b>{@code submitterId} 参数：可见范围恒为「本人提交的」，
 * 提交人从登录态取。与 {@code ComplaintQuery} 不同 —— 投诉天然是双方可见，
 * 所以那边需要 {@code role} 去表达视角；产品反馈没有「双方」概念。</p>
 *
 * @author 银龄伴诊团队
 * @since V6 · 产品反馈
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "我的反馈列表查询入参")
public class FeedbackQuery extends PageQuery<ProductFeedback> {

    @Schema(description = "状态筛选：PENDING / PROCESSING / RESOLVED / REJECTED", example = "PENDING")
    private String status;

    /** MyBatis-Plus 分页对象 */
    public Page<ProductFeedback> toMpPage() {
        return toPage(new Page<>());
    }
}

package org.company.nianglin.dto;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.company.nianglin.common.PageQuery;
import org.company.nianglin.entity.OrderReview;

/**
 * 陪诊员评价列表查询入参（{@code docs/api/06-review-complaint.md} §3）。
 *
 * <p>陪诊员 ID 走路径参数而不是查询参数 —— 它是一个<b>资源标识</b>，
 * 不是一个筛选条件。放在查询串里会让「/api/review/companion?companionId=1」
 * 与「/api/review/companion/1」两种写法同时存在，
 * 前端只要有一处写错就会静默查到别人的评价。</p>
 *
 * @author 银龄伴诊团队
 * @since M7
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "陪诊员评价列表查询入参")
public class ReviewQuery extends PageQuery<OrderReview> {

    @Schema(description = "只看评分 ≥ N 星（1–5）", example = "4")
    @Min(value = 1, message = "评分筛选不能低于 1 星")
    @Max(value = 5, message = "评分筛选不能高于 5 星")
    private Integer minScore;

    @Schema(description = "只看带文字的评价；不传返回全部", example = "true")
    private Boolean hasContent;

    /** MyBatis-Plus 分页对象 */
    public Page<OrderReview> toMpPage() {
        return toPage(new Page<>());
    }
}

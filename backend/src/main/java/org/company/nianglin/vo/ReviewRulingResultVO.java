package org.company.nianglin.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 管理员评价有效性裁定响应（E4 评价公信力闭环）。
 *
 * <p>对应 {@code docs/api/06-review-complaint.md} §X「评价裁定」。</p>
 *
 * @author 银龄伴诊团队
 * @since E4
 */
@Data
@Accessors(chain = true)
@Schema(description = "评价裁定响应")
public class ReviewRulingResultVO {

    @Schema(description = "评价 ID", example = "30001")
    private Long reviewId;

    @Schema(description = "裁定结论（恒为 false，本期不支持恢复有效）", example = "false")
    private Boolean isValid;

    @Schema(description = "被裁定陪诊员用户 ID，前端可据此跳转到陪诊员评分页",
            example = "307")
    private Long companionId;

    @Schema(description = "家属此时是否可以重新评价本单（裁定无效后给出的正向出口，closure-2026-11 · P1-2）",
            example = "true")
    private Boolean canReReview;

    /**
     * 旧的三参构造（{@code canReReview} 缺省为 false）。
     *
     * <p>保留它是为了不打断既有调用方；新代码请用四参重载，
     * 把「还能不能重评」这个事实显式写出来。</p>
     */
    public static ReviewRulingResultVO of(Long reviewId, boolean isValid, Long companionId) {
        return of(reviewId, isValid, companionId, false);
    }

    public static ReviewRulingResultVO of(Long reviewId, boolean isValid, Long companionId,
                                          boolean canReReview) {
        return new ReviewRulingResultVO()
                .setReviewId(reviewId)
                .setIsValid(isValid)
                .setCompanionId(companionId)
                .setCanReReview(canReReview);
    }
}
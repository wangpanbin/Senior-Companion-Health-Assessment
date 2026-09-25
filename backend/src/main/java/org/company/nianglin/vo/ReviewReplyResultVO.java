package org.company.nianglin.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * 陪诊员回复评价的响应（E4 评价公信力闭环）。
 *
 * <p>对应 {@code docs/api/06-review-complaint.md} §X「回复评价」。</p>
 *
 * <h3>为什么独立 VO 而不是复用 {@link ReviewVO}</h3>
 *
 * <p>{@link ReviewVO} 含家属姓名 / 评分 / 标签等字段 —— 这些对「陪诊员已回复」这件事毫无价值，
 * 而且带回去会让前端的赋值逻辑变成「万一这两个字段都拿到了到底用哪个」的判断题。
 * 一句话能说完的事就该用一句话能传完的形状。</p>
 *
 * @author 银龄伴诊团队
 * @since E4
 */
@Data
@Accessors(chain = true)
@Schema(description = "回复评价的响应")
public class ReviewReplyResultVO {

    @Schema(description = "评价 ID", example = "2001")
    private Long reviewId;

    @Schema(description = "回复内容（与请求体一致）", example = "当日 08:20 已到院打卡（可查证），迟到或因老人下楼较慢。")
    private String companionReply;

    @Schema(description = "回复时间", example = "2026-09-25 10:30:00")
    private LocalDateTime replyTime;

    public static ReviewReplyResultVO of(Long reviewId, String companionReply, LocalDateTime replyTime) {
        return new ReviewReplyResultVO()
                .setReviewId(reviewId)
                .setCompanionReply(companionReply)
                .setReplyTime(replyTime);
    }
}
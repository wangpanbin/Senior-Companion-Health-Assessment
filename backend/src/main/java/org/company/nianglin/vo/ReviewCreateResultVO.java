package org.company.nianglin.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;
import org.company.nianglin.constant.OrderStatus;

/**
 * 提交评价的返回（{@code docs/api/06-review-complaint.md} §1）。
 *
 * <p>回传订单的新状态，是为了让前端<b>不必再查一次订单详情</b>就知道
 * 「这一单已经到终态了」。差评与好评走的是同一条路径 ——
 * 评价本身不影响订单能否完成，只影响它是否还需要出现在「待评价」列表里。</p>
 *
 * @author 银龄伴诊团队
 * @since M7
 */
@Data
@Accessors(chain = true)
@Schema(description = "提交评价结果")
public class ReviewCreateResultVO {

    @Schema(description = "新增的评价 ID", example = "2001")
    private Long reviewId;

    @Schema(description = "订单最新状态（提交评价后固定为 REVIEWED）", example = "REVIEWED")
    private String orderStatus;

    @Schema(description = "订单状态中文名", example = "已评价")
    private String orderStatusLabel;

    public static ReviewCreateResultVO of(Long reviewId, OrderStatus status) {
        return new ReviewCreateResultVO()
                .setReviewId(reviewId)
                .setOrderStatus(status == null ? null : status.name())
                .setOrderStatusLabel(status == null ? null : status.getLabel());
    }
}

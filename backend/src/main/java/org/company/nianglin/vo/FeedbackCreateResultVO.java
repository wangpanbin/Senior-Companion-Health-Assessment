package org.company.nianglin.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;
import org.company.nianglin.constant.FeedbackStatus;

/**
 * 提交产品反馈的返回（{@code docs/api/15-product-feedback.md} §1）。
 *
 * <p>回传初始状态（固定 {@code PENDING}）而不是什么都不返回：前端拿到它就能
 * 直接把「待处理」标签渲染出来，不必假设「提交后一定是待处理」——
 * 这个假设一旦将来出现「管理员代录」就会失效（与 {@code ComplaintCreateResultVO} 同款）。</p>
 *
 * @author 银龄伴诊团队
 * @since V6 · 产品反馈
 */
@Data
@Accessors(chain = true)
@Schema(description = "提交产品反馈结果")
public class FeedbackCreateResultVO {

    @Schema(description = "新增的反馈 ID", example = "5001")
    private Long feedbackId;

    @Schema(description = "处理状态", example = "PENDING")
    private String status;

    @Schema(description = "处理状态中文名", example = "待处理")
    private String statusLabel;

    public static FeedbackCreateResultVO of(Long feedbackId, FeedbackStatus status) {
        return new FeedbackCreateResultVO()
                .setFeedbackId(feedbackId)
                .setStatus(status == null ? null : status.name())
                .setStatusLabel(status == null ? null : status.getLabel());
    }
}

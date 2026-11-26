package org.company.nianglin.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;
import org.company.nianglin.constant.FeedbackStatus;

import java.time.LocalDateTime;

/**
 * 处理产品反馈结果（{@code docs/api/15-product-feedback.md} §5）。
 *
 * @author 银龄伴诊团队
 * @since V6 · 产品反馈
 */
@Data
@Accessors(chain = true)
@Schema(description = "处理产品反馈结果")
public class FeedbackHandleResultVO {

    @Schema(description = "反馈 ID", example = "5001")
    private Long feedbackId;

    @Schema(description = "处理后的状态：PROCESSING / RESOLVED / REJECTED", example = "RESOLVED")
    private String status;

    @Schema(description = "状态中文名", example = "已采纳")
    private String statusLabel;

    @Schema(description = "处理时间", example = "2026-11-20 10:30:00")
    private LocalDateTime handleTime;

    public static FeedbackHandleResultVO of(Long feedbackId, FeedbackStatus status, LocalDateTime handleTime) {
        return new FeedbackHandleResultVO()
                .setFeedbackId(feedbackId)
                .setStatus(status == null ? null : status.name())
                .setStatusLabel(status == null ? null : status.getLabel())
                .setHandleTime(handleTime);
    }
}

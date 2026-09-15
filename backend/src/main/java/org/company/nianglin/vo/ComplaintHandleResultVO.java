package org.company.nianglin.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;
import org.company.nianglin.constant.ComplaintStatus;

import java.time.LocalDateTime;

/**
 * 处理投诉结果（{@code docs/api/08-admin.md} §11）。
 *
 * @author 银龄伴诊团队
 * @since M9
 */
@Data
@Accessors(chain = true)
@Schema(description = "处理投诉结果")
public class ComplaintHandleResultVO {

    @Schema(description = "投诉 ID", example = "3001")
    private Long complaintId;

    @Schema(description = "处理后的状态：PROCESSING / RESOLVED / REJECTED", example = "RESOLVED")
    private String status;

    @Schema(description = "状态中文名", example = "已结案")
    private String statusLabel;

    @Schema(description = "处理时间", example = "2026-09-21 10:30:00")
    private LocalDateTime handleTime;

    public static ComplaintHandleResultVO of(Long complaintId, ComplaintStatus status, LocalDateTime handleTime) {
        return new ComplaintHandleResultVO()
                .setComplaintId(complaintId)
                .setStatus(status == null ? null : status.name())
                .setStatusLabel(status == null ? null : status.getLabel())
                .setHandleTime(handleTime);
    }
}

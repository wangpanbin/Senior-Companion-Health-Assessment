package org.company.nianglin.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;
import org.company.nianglin.constant.ComplaintStatus;

/**
 * 提交投诉的返回（{@code docs/api/06-review-complaint.md} §5）。
 *
 * <p>回传初始状态（固定 {@code PENDING}）而不是什么都不返回：
 * 前端拿到它就能直接把「待处理」标签渲染出来，
 * 不必假设「提交后一定是待处理」—— 这个假设在管理员端有「代用户提交」时就会失效。</p>
 *
 * @author 银龄伴诊团队
 * @since M7
 */
@Data
@Accessors(chain = true)
@Schema(description = "提交投诉结果")
public class ComplaintCreateResultVO {

    @Schema(description = "新增的投诉 ID", example = "3001")
    private Long complaintId;

    @Schema(description = "处理状态", example = "PENDING")
    private String status;

    @Schema(description = "处理状态中文名", example = "待处理")
    private String statusLabel;

    public static ComplaintCreateResultVO of(Long complaintId, ComplaintStatus status) {
        return new ComplaintCreateResultVO()
                .setComplaintId(complaintId)
                .setStatus(status == null ? null : status.name())
                .setStatusLabel(status == null ? null : status.getLabel());
    }
}

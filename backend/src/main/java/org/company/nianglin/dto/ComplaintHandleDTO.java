package org.company.nianglin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 处理投诉入参（{@code docs/api/08-admin.md} §11）。
 *
 * <p>{@code status} 只接受 {@code PROCESSING} / {@code RESOLVED} / {@code REJECTED}。
 * 不能传 {@code PENDING} —— 那等于「把已处理的投诉退回待处理」，
 * 与 {@code ComplaintStatus} 的「只可正向流转」直接冲突，
 * 也会让「处理时间」这类审计信息失去意义。</p>
 *
 * @author 银龄伴诊团队
 * @since M9
 */
@Data
@Schema(description = "处理投诉入参")
public class ComplaintHandleDTO {

    @Schema(description = "目标状态：PROCESSING / RESOLVED / REJECTED", example = "RESOLVED",
            requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "请指定要流转到的状态")
    private String status;

    @Schema(description = "处理结果说明，10–500 字符", example = "已核实陪诊员迟到，扣除信用分 5 分。",
            requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "请填写处理结果说明")
    @Size(min = 10, max = 500, message = "处理结果说明长度应为 10–500 个字符")
    private String handleResult;

    @Schema(description = "是否对被投诉人计违规", example = "true")
    private Boolean penaltyToTarget;
}

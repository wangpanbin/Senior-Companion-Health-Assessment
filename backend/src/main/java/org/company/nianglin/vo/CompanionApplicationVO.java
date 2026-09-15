package org.company.nianglin.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;
import org.company.nianglin.constant.AuditStatus;
import org.company.nianglin.entity.CompanionAuditRecord;

import java.time.LocalDateTime;

/**
 * 陪诊员资质申请状态（本人查看）。
 *
 * <p>对应 {@code GET /api/user/companion/application}（{@code docs/api/02-elder-family.md} §4）。
 * 无申请记录时接口返回 {@code data: null}，而不是空对象 ——
 * 前端判空比判一堆字段是否为空更省事。</p>
 *
 * @author 银龄伴诊团队
 * @since M3
 */
@Data
@Accessors(chain = true)
@Schema(description = "陪诊员资质申请状态")
public class CompanionApplicationVO {

    @Schema(description = "申请记录 ID", example = "501")
    private Long applicationId;

    @Schema(description = "审核状态枚举名", example = "REJECTED")
    private String auditStatus;

    @Schema(description = "审核状态中文", example = "已驳回")
    private String auditStatusLabel;

    @Schema(description = "驳回原因，仅状态为 REJECTED 时返回",
            example = "身份证照片不清晰，请重新上传")
    private String rejectReason;

    @Schema(description = "提交时间", example = "2026-09-10 09:00:00")
    private LocalDateTime submitTime;

    @Schema(description = "审核时间，未审核时为 null", example = "2026-09-11 14:30:00")
    private LocalDateTime auditTime;

    public static CompanionApplicationVO of(CompanionAuditRecord r) {
        if (r == null) {
            return null;
        }
        boolean rejected = AuditStatus.REJECTED.name().equalsIgnoreCase(r.getAuditStatus());
        return new CompanionApplicationVO()
                .setApplicationId(r.getId())
                .setAuditStatus(r.getAuditStatus())
                .setAuditStatusLabel(AuditStatus.labelOf(r.getAuditStatus()))
                // 只有被驳回才回传原因；PENDING/APPROVED 下这个字段本该为空，
                // 万一库里残留了历史驳回原因，也不能漏给前端
                .setRejectReason(rejected ? r.getRejectReason() : null)
                .setSubmitTime(r.getSubmitTime())
                .setAuditTime(r.getAuditTime());
    }
}

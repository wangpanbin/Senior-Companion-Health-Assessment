package org.company.nianglin.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;
import org.company.nianglin.constant.AuditStatus;

/**
 * 资质审核结果（{@code docs/api/08-admin.md} §3）。
 *
 * <p>回传审核后的状态而不是空响应：前端拿到 {@code REJECTED} 就能直接
 * 把「已驳回」渲染出来，不必自己推断「点了驳回按钮，所以状态应该是已驳回」——
 * 而一旦后端因为并发或幂等把请求处理成「已经是终态，跳过」，
 * 前端的推断就会和数据库不一致。</p>
 *
 * @author 银龄伴诊团队
 * @since M9
 */
@Data
@Accessors(chain = true)
@Schema(description = "资质审核结果")
public class AuditDecisionResultVO {

    @Schema(description = "申请 ID", example = "501")
    private Long applicationId;

    @Schema(description = "审核后状态：APPROVED / REJECTED", example = "REJECTED")
    private String auditStatus;

    @Schema(description = "审核后状态中文", example = "已驳回")
    private String auditStatusLabel;

    public static AuditDecisionResultVO of(Long applicationId, AuditStatus status) {
        return new AuditDecisionResultVO()
                .setApplicationId(applicationId)
                .setAuditStatus(status == null ? null : status.name())
                .setAuditStatusLabel(status == null ? null : status.getLabel());
    }
}

package org.company.nianglin.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;
import org.company.nianglin.constant.AuditStatus;

/**
 * 资质申请提交结果。
 *
 * <p>对应 {@code POST /api/user/companion/apply}（{@code docs/api/02-elder-family.md} §3）。</p>
 *
 * <p>刻意<b>不回传所提交的姓名、身份证、证件清单</b> ——
 * 提交成功只需要告诉前端「拿到申请号了、当前状态是待审核」，
 * 把敏感材料原样回显一遍没有任何用处。</p>
 *
 * @author 银龄伴诊团队
 * @since M3
 */
@Data
@Accessors(chain = true)
@Schema(description = "资质申请提交结果")
public class CompanionApplyResultVO {

    @Schema(description = "申请记录 ID", example = "501")
    private Long applicationId;

    @Schema(description = "审核状态枚举名，固定为 PENDING", example = "PENDING")
    private String auditStatus;

    @Schema(description = "审核状态中文", example = "待审核")
    private String auditStatusLabel;

    public static CompanionApplyResultVO of(Long applicationId) {
        return new CompanionApplyResultVO()
                .setApplicationId(applicationId)
                .setAuditStatus(AuditStatus.PENDING.name())
                .setAuditStatusLabel(AuditStatus.PENDING.getLabel());
    }
}

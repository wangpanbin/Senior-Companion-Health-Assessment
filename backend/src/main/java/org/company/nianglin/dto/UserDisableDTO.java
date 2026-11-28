package org.company.nianglin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.company.nianglin.constant.ActiveOrderAction;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 封禁用户入参（{@code docs/api/08-admin.md} §5）。
 *
 * <p>封禁原因<b>必填</b>且写进站内信发给用户：封禁是一个会直接改变
 * 用户体验的动作，一句「账号已被封禁」而不说原因，用户的下一步动作
 * 必然是打电话来问 —— 管理成本最终还是回到平台自己身上。</p>
 *
 * @author 银龄伴诊团队
 * @since M9
 */
@Data
@Schema(description = "封禁用户入参")
public class UserDisableDTO {

    @Schema(description = "封禁原因，5–200 字符", example = "多次爽约且未提前告知家属",
            requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "请填写封禁原因")
    @Size(min = 5, max = 200, message = "封禁原因长度应为 5–200 个字符")
    private String reason;

    /**
     * 在手订单的处置方式（closure-2026-11 · P0-4）。
     *
     * <p>可选。封禁陪诊员时若他名下还有「已接单 / 服务中」的订单，
     * 不指定处置方式接口会回 {@code 2012 COMPANION_HAS_ACTIVE_ORDER}
     * 并在 {@code data.activeOrderCount} 里带上张数，前端据此弹处置选择框；
     * 指定了就按该方式处置后再封禁。</p>
     *
     * <p>对家属 / 老人账号一律忽略该字段 —— 他们名下不会有「在手订单」。</p>
     */
    @Schema(description = "在手订单处置方式：RELEASE-放回大厅 / CANCEL-强制取消（封禁陪诊员时必填其一）")
    private ActiveOrderAction activeOrderAction;
}

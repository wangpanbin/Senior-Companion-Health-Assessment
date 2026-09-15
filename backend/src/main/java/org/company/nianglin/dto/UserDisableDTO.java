package org.company.nianglin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
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
}

package org.company.nianglin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 资质审核入参（{@code docs/api/08-admin.md} §3）。
 *
 * <h3>驳回原因用 {@code @Size} 而不是 {@code @NotBlank}</h3>
 *
 * <p>「通过时不填、驳回时必须填」是<b>条件必填</b>，注解表达不了。
 * 因此这里只校验长度上限，必填判断放在 Service：
 * {@code approved == false} 且原因为空 → {@code 8003}。
 * 若强行用 {@code @NotBlank}，通过的请求也必须带一个原因才能过校验 ——
 * 那会逼前端在「通过」时编一个假原因，日志反而变得不可信。</p>
 *
 * @author 银龄伴诊团队
 * @since M9
 */
@Data
@Schema(description = "资质审核入参")
public class AuditDecisionDTO {

    @Schema(description = "true 通过 / false 驳回", example = "false",
            requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "请明确审核结论")
    private Boolean approved;

    @Schema(description = "驳回原因，5–200 字符；驳回时必填", example = "身份证照片不清晰，请重新上传")
    @Size(max = 200, message = "驳回原因不能超过 200 个字符")
    private String rejectReason;

    @Schema(description = "管理员内部备注，≤ 200 字符", example = "第二次提交仍模糊")
    @Size(max = 200, message = "内部备注不能超过 200 个字符")
    private String remark;
}

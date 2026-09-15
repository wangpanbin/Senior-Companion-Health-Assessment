package org.company.nianglin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 修改密码请求。
 *
 * <p>⚠️ 三个字段都用 {@code @NotBlank} 且不允许 {@code null}，
 * 校验交给 Bean Validation，Service 里只做业务判断（原密码是否正确、新旧是否相同）。</p>
 *
 * @author 银龄伴诊团队
 */
@Data
@Schema(description = "修改密码请求")
public class ChangePasswordDTO {

    @Schema(description = "原密码", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "请输入原密码")
    @Size(min = 6, max = 32, message = "原密码长度为 6-32 位")
    private String oldPassword;

    @Schema(description = "新密码，6-32 位且需含字母与数字", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "请输入新密码")
    @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d)\\S{6,32}$", message = "新密码为 6-32 位，且需同时包含字母与数字")
    private String newPassword;

    @Schema(description = "确认新密码", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "请再次输入新密码")
    private String confirmPassword;
}

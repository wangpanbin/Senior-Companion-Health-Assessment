package org.company.nianglin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 自助找回密码请求（V4 · P1-1）。
 *
 * <p>对应文档：{@code docs/api/01-auth-user.md} §11。
 * {@code newPassword} 的强度规则与注册 / 修改密码**完全一致**：
 * 6–32 位、至少含 1 个字母与 1 个数字。</p>
 *
 * @author 银龄伴诊团队
 * @since V4 · P1-1
 */
@Data
@Schema(description = "自助找回密码请求")
public class ForgotPasswordDTO {

    @Schema(description = "手机号，11 位", example = "13800000000", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "请输入手机号")
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "请输入正确的 11 位手机号")
    private String phone;

    @Schema(description = "6 位数字短信验证码", example = "123456", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "请输入短信验证码")
    @Pattern(regexp = "^\\d{6}$", message = "验证码为 6 位数字")
    private String smsCode;

    @Schema(description = "新密码，6-32 位且需含字母与数字", example = "NewPass123",
            requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "请输入新密码")
    @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d)\\S{6,32}$", message = "密码为 6-32 位，且需同时包含字母与数字")
    private String newPassword;
}

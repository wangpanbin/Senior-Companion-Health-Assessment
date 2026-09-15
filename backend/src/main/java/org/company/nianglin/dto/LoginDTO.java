package org.company.nianglin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 登录请求。
 *
 * <p>对应文档：{@code docs/api/01-auth-user.md} §3 登录。</p>
 *
 * @author 银龄伴诊团队
 */
@Data
@Schema(description = "登录请求")
public class LoginDTO {

    @Schema(description = "手机号或用户名", example = "family001", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "请输入手机号或用户名")
    @Size(max = 50, message = "账号长度不能超过 50 位")
    private String username;

    @Schema(description = "密码", example = "Nl@123456", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "请输入密码")
    @Size(min = 6, max = 32, message = "密码长度为 6-32 位")
    private String password;

    @Schema(description = "验证码标识，来自 GET /api/auth/captcha", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "请先获取验证码")
    private String captchaKey;

    @Schema(description = "验证码", example = "8F3K", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "请输入验证码")
    @Size(min = 4, max = 6, message = "验证码为 4-6 位")
    private String captchaCode;
}

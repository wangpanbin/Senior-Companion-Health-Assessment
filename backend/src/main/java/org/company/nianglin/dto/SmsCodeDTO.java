package org.company.nianglin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 发送找回密码短信验证码请求（V4 · P1-1）。
 *
 * <p>对应文档：{@code docs/api/01-auth-user.md} §10。</p>
 *
 * @author 银龄伴诊团队
 * @since V4 · P1-1
 */
@Data
@Schema(description = "发送找回密码短信验证码请求")
public class SmsCodeDTO {

    @Schema(description = "手机号，11 位", example = "13800000000", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "请输入手机号")
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "请输入正确的 11 位手机号")
    private String phone;
}

package org.company.nianglin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 重置密码入参（{@code docs/api/08-admin.md} §7）。
 *
 * <p>重置原因必填：这是管理员<b>代替用户改掉登录凭据</b>的操作，
 * 也是这类系统里最容易被滥用的一个接口（管理员可以借此登录任意账号）。
 * 强制填原因让每一次调用都在日志里留下「为什么」，
 * 事后审计时至少能看出哪几次是伪装成客服请求的。</p>
 *
 * @author 银龄伴诊团队
 * @since M9
 */
@Data
@Schema(description = "重置密码入参")
public class ResetPasswordDTO {

    @Schema(description = "重置原因，必填", example = "用户来电请求重置，已核验身份",
            requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "请填写重置原因")
    @Size(max = 200, message = "重置原因不能超过 200 个字符")
    private String remark;
}

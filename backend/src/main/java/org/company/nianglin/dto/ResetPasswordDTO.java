package org.company.nianglin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
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
 * <p><b>为什么增加可选的 {@code newPassword}</b>：此前本接口只能「重置为部署配置的
 * 默认口令」，而默认口令<b>不随响应下发</b>，站内信正文也不含它 ——
 * 结果是用户收到了「密码已重置」的站内信，却永远不知道自己该用哪个密码登录，
 * 账号就此被永久锁死，管理员重置多少次都一样。这是死循环，不是文案问题。</p>
 *
 * <p>因此本接口增加可选的 {@code newPassword}：
 * <ul>
 *   <li><b>传了</b>：就用管理员指定的口令，由管理员线下/电话转交用户；</li>
 *   <li><b>没传</b>：沿用部署配置的默认口令（保持既有行为与后管脚本兼容）。
 *   </li>
 * </ul>
 *
 * <p>⚠️ <b>响应体永远不含任何口令字段</b>（含管理员自设口令）—— 那是仓库自己的安全
 * 验收判据，见 {@code docs/security/安全评估报告-2026-10-18.md} §8.2 P0-3。
 * 管理员要转交的那份口令由**前端**用他刚输入的值展示，服务端不回传、不记录。</p>
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

    /**
     * 管理员指定的新密码（可选）。
     *
     * <p>不传则回退部署配置的默认口令。复杂度口径与注册
     * （{@code RegisterDTO#password}）保持一致：6-32 位且需同时含字母与数字。</p>
     */
    @Schema(description = "管理员指定的新密码（可选）；不传则使用部署配置的默认口令",
            example = "Abc123456")
    @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d)\\S{6,32}$",
            message = "密码为 6-32 位，且需同时包含字母与数字")
    private String newPassword;
}

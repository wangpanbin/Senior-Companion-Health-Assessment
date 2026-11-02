package org.company.nianglin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 注销账号请求（V4 · P0-1）。
 *
 * <p>对应 {@code docs/api/01-auth-user.md} §8。两个字段缺一不可：</p>
 * <ul>
 *   <li>{@code password} —— 复验当前密码，防止他人拿着未锁屏的设备把账号注销掉；</li>
 *   <li>{@code confirmText} —— 固定串「我已知晓注销后果」，前端要求用户手打，
 *       后端逐字比对，把「手滑点错」与「真的想清楚了」区分开。</li>
 * </ul>
 *
 * @author 银龄伴诊团队
 * @since V4
 */
@Data
@Schema(description = "注销账号请求")
public class DeactivateAccountDTO {

    @Schema(description = "当前密码（复验）", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "请输入当前密码")
    @Size(min = 6, max = 32, message = "密码长度为 6-32 位")
    private String password;

    @Schema(description = "二次确认文本，必须原样输入「我已知晓注销后果」",
            requiredMode = Schema.RequiredMode.REQUIRED, example = "我已知晓注销后果")
    @NotBlank(message = "请输入确认文本")
    private String confirmText;
}

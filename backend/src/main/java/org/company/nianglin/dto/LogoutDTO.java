package org.company.nianglin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 登出请求（可选请求体）。
 *
 * <p>访问令牌通过 {@code Authorization} 请求头传递，无需放在 body 里；
 * 这里只承载可选的 {@code refreshToken}，用于「登出后连刷新令牌一起作废」。</p>
 *
 * <p>⚠️ {@code refreshToken} 刻意<b>不加</b> {@code @NotBlank}：登出是用户的退路，
 * 不能因为少传一个附属令牌就让用户退不出去。没传就只拉黑访问令牌。</p>
 *
 * @author 银龄伴诊团队
 */
@Data
@Schema(description = "登出请求（可选请求体）")
public class LogoutDTO {

    @Schema(description = "可选。登录时下发的 refreshToken，传入则一并作废")
    private String refreshToken;
}

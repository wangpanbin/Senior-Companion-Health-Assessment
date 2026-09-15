package org.company.nianglin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 刷新令牌请求。
 *
 * @author 银龄伴诊团队
 */
@Data
@Schema(description = "刷新令牌请求")
public class RefreshTokenDTO {

    @Schema(description = "登录时下发的 refreshToken", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "缺少 refreshToken")
    private String refreshToken;
}

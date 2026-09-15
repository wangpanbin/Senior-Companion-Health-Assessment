package org.company.nianglin.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 登录结果。
 *
 * @author 银龄伴诊团队
 */
@Data
@Accessors(chain = true)
@Schema(description = "登录结果")
public class LoginVO {

    @Schema(description = "访问令牌，放在 Authorization: Bearer <token> 请求头")
    private String accessToken;

    @Schema(description = "刷新令牌，accessToken 过期后用它换新的访问令牌")
    private String refreshToken;

    @Schema(description = "访问令牌有效期（秒）", example = "7200")
    private Long expiresIn;

    @Schema(description = "令牌类型，固定 Bearer", example = "Bearer")
    private String tokenType = "Bearer";

    @Schema(description = "用户信息（手机号已脱敏）")
    private UserInfoVO userInfo;
}

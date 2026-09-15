package org.company.nianglin.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 图形验证码。
 *
 * @author 银龄伴诊团队
 */
@Data
@Accessors(chain = true)
@Schema(description = "图形验证码")
public class CaptchaVO {

    @Schema(description = "验证码标识，登录/注册时原样回传", example = "a1b2c3d4e5f67890abcdef1234567890")
    private String captchaKey;

    @Schema(description = "base64 图片，前端直接放 <img :src>", example = "data:image/png;base64,iVBORw0KGgo...")
    private String captchaImage;

    @Schema(description = "有效期（秒）", example = "300")
    private Long expiresIn;
}

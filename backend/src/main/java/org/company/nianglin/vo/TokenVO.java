package org.company.nianglin.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 刷新令牌后的结果。
 *
 * <p>只换 {@code accessToken}，不换 {@code refreshToken} ——
 * 刷新令牌用的是 7 天长效期，没必要每次刷新都滚动，否则「刷新一次延长 7 天」
 * 会让令牌实际永不过期，等于没有过期策略。</p>
 *
 * @author 银龄伴诊团队
 */
@Data
@Accessors(chain = true)
@Schema(description = "刷新令牌结果")
public class TokenVO {

    @Schema(description = "新的访问令牌")
    private String accessToken;

    @Schema(description = "访问令牌有效期（秒）", example = "7200")
    private Long expiresIn;

    @Schema(description = "令牌类型，固定 Bearer", example = "Bearer")
    private String tokenType = "Bearer";
}

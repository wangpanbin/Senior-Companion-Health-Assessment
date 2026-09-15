package org.company.nianglin.security;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * JWT 配置。
 *
 * <p>绑定 {@code application.yml} 的 {@code nianglin.jwt.*}，与
 * {@code docs/api/README.md}「2.2 Token 规格」保持一致。</p>
 *
 * <p>⚠️ 生产环境必须通过环境变量 {@code JWT_SECRET} 覆盖密钥。密钥一旦更换，
 * 所有已签发令牌立即失效（用户需重新登录），这是预期行为。</p>
 *
 * @author 银龄伴诊团队
 */
@Data
@Component
@ConfigurationProperties(prefix = "nianglin.jwt")
public class JwtProperties {

    /**
     * 签名密钥（HS256）。长度必须 ≥ 32 字节，否则启动即失败。
     */
    private String secret;

    /**
     * accessToken 有效期（分钟），默认 120。
     */
    private long expireMinutes = 120L;

    /**
     * refreshToken 有效期（天），默认 7。
     */
    private long refreshExpireDays = 7L;

    /**
     * 读取令牌的请求头名，默认 {@code Authorization}。
     */
    private String header = "Authorization";

    /**
     * 令牌前缀，注意末尾有且只有一个空格（{@code "Bearer "}）。
     */
    private String prefix = "Bearer ";

    /** accessToken 有效期（秒） */
    public long accessTtlSeconds() {
        return expireMinutes * 60L;
    }

    /** refreshToken 有效期（秒） */
    public long refreshTtlSeconds() {
        return refreshExpireDays * 24L * 3600L;
    }
}

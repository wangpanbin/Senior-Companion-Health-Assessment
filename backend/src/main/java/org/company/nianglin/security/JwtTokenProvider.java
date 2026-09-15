package org.company.nianglin.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.company.nianglin.common.ResultCode;
import org.company.nianglin.exception.BusinessException;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * JWT 签发与解析。
 *
 * <p>算法固定 HS256。载荷字段见 {@link TokenPayload}。</p>
 *
 * <p><b>为什么用 HS256 而不是 RS256</b>：本项目是单体应用，签发与校验在同一进程内，
 * 非对称密钥带来的收益（多服务共享公钥）不存在，反而增加密钥管理成本。</p>
 *
 * @author 银龄伴诊团队
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtTokenProvider {

    /** 访问令牌类型标记 */
    public static final String TYPE_ACCESS = "access";

    /** 刷新令牌类型标记 */
    public static final String TYPE_REFRESH = "refresh";

    private static final String CLAIM_ROLE = "role";
    private static final String CLAIM_USERNAME = "username";
    private static final String CLAIM_TYPE = "typ";
    private static final String CLAIM_PASSWORD_VERSION = "ver";

    /** HS256 要求密钥不短于 256 bit（32 字节） */
    private static final int MIN_SECRET_BYTES = 32;

    private final JwtProperties properties;

    private SecretKey secretKey;

    /**
     * 校验并派生签名密钥。
     *
     * <p>密钥过短时<b>直接启动失败</b>，而不是悄悄降级或用短密钥签名 ——
     * 后者会产出一批「看起来能用、实际可被暴力破解」的令牌，属于安全隐患。</p>
     */
    @PostConstruct
    void init() {
        byte[] bytes = properties.getSecret() == null
                ? new byte[0]
                : properties.getSecret().getBytes(StandardCharsets.UTF_8);
        if (bytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "nianglin.jwt.secret 长度不足 " + MIN_SECRET_BYTES + " 字节，HS256 无法安全签名；"
                            + "请通过环境变量 JWT_SECRET 配置至少 32 字节的随机密钥");
        }
        this.secretKey = Keys.hmacShaKeyFor(bytes);
    }

    /** 签发访问令牌 */
    public String createAccessToken(Long userId, String username, String role, int passwordVersion) {
        return create(userId, username, role, passwordVersion, TYPE_ACCESS, properties.accessTtlSeconds());
    }

    /** 签发刷新令牌 */
    public String createRefreshToken(Long userId, String username, String role, int passwordVersion) {
        return create(userId, username, role, passwordVersion, TYPE_REFRESH, properties.refreshTtlSeconds());
    }

    private String create(Long userId, String username, String role, int passwordVersion,
                          String type, long ttlSeconds) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim(CLAIM_USERNAME, username)
                .claim(CLAIM_ROLE, role)
                .claim(CLAIM_TYPE, type)
                .claim(CLAIM_PASSWORD_VERSION, passwordVersion)
                .id(UUID.randomUUID().toString().replace("-", ""))
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(ttlSeconds)))
                .signWith(secretKey, Jwts.SIG.HS256)
                .compact();
    }

    /**
     * 解析并验签。
     *
     * <p>任何形式的失败（签名不对、已过期、结构被篡改、{@code sub} 不是数字）统一抛
     * {@link ResultCode#TOKEN_INVALID}，对外不区分具体原因 —— 否则等于给攻击者做提示。</p>
     */
    public TokenPayload parse(String token) {
        if (token == null || token.isBlank()) {
            throw new BusinessException(ResultCode.TOKEN_INVALID);
        }
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(secretKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            Integer version = claims.get(CLAIM_PASSWORD_VERSION, Integer.class);
            // 缺失 ver 字段必须拒绝：兜底默认 0 会与 TokenStore.currentPasswordVersion 的默认 0 重合，
            // 一旦 Redis 被清空或首次登录用户，所有 ver=0 的旧令牌（含被改密作废的）会重新生效
            if (version == null) {
                throw new BusinessException(ResultCode.TOKEN_INVALID);
            }
            return new TokenPayload(
                    Long.valueOf(claims.getSubject()),
                    claims.get(CLAIM_USERNAME, String.class),
                    claims.get(CLAIM_ROLE, String.class),
                    claims.get(CLAIM_TYPE, String.class),
                    claims.getId(),
                    version,
                    claims.getIssuedAt().getTime(),
                    claims.getExpiration().getTime());
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("JWT 校验未通过：{}", e.getMessage());
            throw new BusinessException(ResultCode.TOKEN_INVALID);
        }
    }

    /** 令牌的剩余有效秒数，已过期返回 0 */
    public long remainingSeconds(long expiresAtMillis) {
        long remain = Duration.between(Instant.now(), Instant.ofEpochMilli(expiresAtMillis)).getSeconds();
        return Math.max(remain, 0L);
    }

    /**
     * 从请求头取值中提取令牌。
     *
     * <p>兼容两种写法：标准 {@code Bearer <token>} 与裸 token（方便 curl 联调）。
     * 缺省即返回 {@code null}，交由调用方决定「未登录」还是「忽略」。</p>
     */
    public String resolveFromHeader(String headerValue) {
        if (headerValue == null || headerValue.isBlank()) {
            return null;
        }
        String prefix = properties.getPrefix();
        String value = headerValue.trim();
        if (prefix != null && !prefix.isEmpty() && value.startsWith(prefix)) {
            value = value.substring(prefix.length()).trim();
        }
        return value.isEmpty() ? null : value;
    }
}

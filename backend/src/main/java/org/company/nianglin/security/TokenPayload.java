package org.company.nianglin.security;

/**
 * JWT 载荷的解析结果（不可变）。
 *
 * <p>与 {@code docs/api/README.md}「2.2 Token 规格」的载荷字段一一对应：
 * {@code sub}（用户 ID）、{@code role}、{@code iat}、{@code exp}，
 * 另加三个内部字段：{@code typ}（access / refresh）、{@code jti}（登出黑名单用）、
 * {@code ver}（密码版本，改密后旧令牌立即失效）。</p>
 *
 * @param userId           用户 ID（对应 JWT 的 {@code sub}）
 * @param username         登录用户名（仅用于日志，不参与鉴权）
 * @param role             角色枚举名：ELDER / FAMILY / COMPANION / ADMIN
 * @param tokenType        {@link JwtTokenProvider#TYPE_ACCESS} 或 {@link JwtTokenProvider#TYPE_REFRESH}
 * @param jti              令牌唯一标识，登出时按它拉黑
 * @param passwordVersion  签发时的密码版本号
 * @param issuedAtMillis   签发时间（毫秒时间戳）
 * @param expiresAtMillis  过期时间（毫秒时间戳）
 * @author 银龄伴诊团队
 */
public record TokenPayload(
        Long userId,
        String username,
        String role,
        String tokenType,
        String jti,
        int passwordVersion,
        long issuedAtMillis,
        long expiresAtMillis) {

    /** 是否为访问令牌 */
    public boolean isAccess() {
        return JwtTokenProvider.TYPE_ACCESS.equals(tokenType);
    }

    /** 是否为刷新令牌 */
    public boolean isRefresh() {
        return JwtTokenProvider.TYPE_REFRESH.equals(tokenType);
    }
}

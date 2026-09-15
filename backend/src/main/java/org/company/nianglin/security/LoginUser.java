package org.company.nianglin.security;

import org.company.nianglin.constant.RoleConstants;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.Collection;
import java.util.List;

/**
 * 登录态主体（Spring Security {@code Authentication#getPrincipal()} 的实际类型）。
 *
 * <p>由 {@link JwtAuthenticationFilter} 从 JWT 载荷构造，<b>不查数据库</b>——
 * 这是无状态鉴权的关键：每个请求只做一次签名校验与 Redis 黑名单比对。</p>
 *
 * <p>⚠️ 本对象只承载鉴权所需的最小信息，不含手机号、身份证号、密码等敏感字段，
 * 避免它们随日志或调试输出外泄。</p>
 *
 * @param userId          用户 ID
 * @param username        登录用户名
 * @param role            角色枚举名
 * @param passwordVersion 签发时的密码版本号
 * @param jti             当前令牌的唯一标识（登出时用于拉黑）
 * @param expiresAtMillis 当前令牌的过期时间戳（登出时用于计算黑名单 TTL）
 * @author 银龄伴诊团队
 */
public record LoginUser(
        Long userId,
        String username,
        String role,
        int passwordVersion,
        String jti,
        long expiresAtMillis) {

    /** Spring Security 权限列表，形如 {@code ROLE_FAMILY} */
    public Collection<GrantedAuthority> authorities() {
        return List.of(new SimpleGrantedAuthority(RoleConstants.ROLE_PREFIX + role));
    }

    /**
     * 是否老年患者。
     *
     * <p>老人账号默认只读，写操作必须由家属代操作（{@code AGENT.md} §4.3 硬约束），
     * 由 {@link ElderReadOnlyInterceptor} 在服务端强制拦截。</p>
     */
    public boolean isElder() {
        return RoleConstants.ELDER.equals(role);
    }

    /** 是否管理员 */
    public boolean isAdmin() {
        return RoleConstants.ADMIN.equals(role);
    }
}

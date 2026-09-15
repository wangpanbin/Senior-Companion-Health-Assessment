package org.company.nianglin.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.company.nianglin.exception.BusinessException;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * JWT 认证过滤器。
 *
 * <p>插在 {@code UsernamePasswordAuthenticationFilter} 之前，负责：
 * 读请求头 → 验签 → 查黑名单 → 比对密码版本 → 写入 {@code SecurityContext}。</p>
 *
 * <p><b>设计要点</b>：本过滤器<b>从不</b>直接返回 401。令牌无效时只是「不认证」并放行，
 * 由下游的授权规则决定结果。这样做的好处是公开接口（如 {@code /api/auth/captcha}）
 * 即使收到一个过期令牌也能正常服务，而不是莫名其妙地 401。</p>
 *
 * @author 银龄伴诊团队
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenProvider tokenProvider;
    private final TokenStore tokenStore;
    private final JwtProperties jwtProperties;

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        String token = tokenProvider.resolveFromHeader(request.getHeader(jwtProperties.getHeader()));

        if (token != null) {
            try {
                TokenPayload payload = tokenProvider.parse(token);
                if (!payload.isAccess()) {
                    // 刷新令牌不能当访问令牌用，否则 refreshToken 泄露即可长期冒充用户
                    log.debug("请求头携带的不是访问令牌（typ={}），按未认证处理", payload.tokenType());
                } else if (tokenStore.isBlacklisted(payload.jti())) {
                    log.debug("令牌已登出（jti={}），按未认证处理", payload.jti());
                } else if (payload.passwordVersion() != tokenStore.currentPasswordVersion(payload.userId())) {
                    // 改密后版本递增，旧令牌立即失效
                    log.debug("令牌密码版本已过期（userId={}），按未认证处理", payload.userId());
                } else {
                    authenticate(request, payload);
                }
            } catch (BusinessException e) {
                SecurityContextHolder.clearContext();
                log.debug("令牌校验未通过 | {} {} | {}", request.getMethod(), request.getRequestURI(), e.getMessage());
            }
        }

        filterChain.doFilter(request, response);
    }

    private void authenticate(HttpServletRequest request, TokenPayload payload) {
        LoginUser loginUser = new LoginUser(
                payload.userId(),
                payload.username(),
                payload.role(),
                payload.passwordVersion(),
                payload.jti(),
                payload.expiresAtMillis());

        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(loginUser, null, loginUser.authorities());
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}

package org.company.nianglin.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.company.nianglin.common.Result;
import org.company.nianglin.common.ResultCode;
import org.company.nianglin.exception.BusinessException;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * JWT 认证过滤器。
 *
 * <p>插在 {@code UsernamePasswordAuthenticationFilter} 之前，负责：
 * 读请求头 → 验签 → 查黑名单 → 比对密码版本 → 查封禁标记 → 写入 {@code SecurityContext}。</p>
 *
 * <p><b>设计要点</b>：本过滤器对「令牌无效」<b>从不</b>直接返回 401，
 * 只是「不认证」并放行，由下游的授权规则决定结果。这样做的好处是公开接口
 * （如 {@code /api/auth/captcha}）即使收到一个过期令牌也能正常服务，
 * 而不是莫名其妙地 401。</p>
 *
 * <p><b>唯一的例外是封禁</b>：一个<b>令牌完全有效</b>但账号已被封禁的请求，
 * 必须就地拦下并返回 403。放行的话，未认证状态会被 Security 当成
 * 「未登录」返回 401，前端只会提示「请重新登录」——
 * 用户于是反复登录、反复被拒，却永远不知道原因是账号被封了。
 * 只有这里返回带 {@code code=1002} 的 403，前端才能把「账号已被封禁」
 * 这句真正有用的提示显示出来。</p>
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
    private final ObjectMapper objectMapper;

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
                } else if (tokenStore.isBanned(payload.userId())) {
                    // 封禁判定必须排在「密码版本」之前。M9 的 disableUser 同时做了两件事：
                    // 打封禁标记（isBanned）与递增密码版本（bumpPasswordVersion）。
                    // 如果先比版本，被封禁的用户会落进「版本过期」那一支按未认证放行，
                    // 最终由 Security 返回 401 —— 前端只会提示「请重新登录」，
                    // 用户于是反复登录、反复被拒，永远不知道原因是账号被封了。
                    // 这正是本类 javadoc 里说要避免的那件事，而它只在两处检查的
                    // 先后顺序正确时才成立。
                    log.info("已封禁账号的令牌被拒绝 | userId={} | {} {}",
                            payload.userId(), request.getMethod(), request.getRequestURI());
                    rejectAsBanned(response);
                    return;
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

    /**
     * 封禁账号就地返回 403。
     *
     * <p>过滤器在 {@code DispatcherServlet} 之外，异常不会走到
     * {@code GlobalExceptionHandler}，因此这里必须自己写响应体；
     * 不写的话前端拿到的是一个空 body 的 403，只能显示「无权限」。</p>
     */
    private void rejectAsBanned(HttpServletResponse response) throws IOException {
        SecurityContextHolder.clearContext();
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(
                Result.fail(ResultCode.ACCOUNT_DISABLED)));
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

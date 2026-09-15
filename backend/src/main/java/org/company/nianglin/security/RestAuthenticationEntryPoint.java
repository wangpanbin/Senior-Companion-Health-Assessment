package org.company.nianglin.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.company.nianglin.common.Result;
import org.company.nianglin.common.ResultCode;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 未认证入口：不带令牌 / 令牌失效时返回 HTTP 401 + 统一响应结构。
 *
 * <p>不配置它的话，Spring Security 会返回一个空的 401 或 HTML 登录页，
 * 前端拿不到 {@code code} 字段，无法触发「刷新令牌并重放原请求」的逻辑。</p>
 *
 * @author 银龄伴诊团队
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    @Override
    public void commence(HttpServletRequest request,
                         HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        log.debug("未认证访问被拦截 | {} {}", request.getMethod(), request.getRequestURI());
        SecurityJsonWriter.write(response, objectMapper, HttpStatus.UNAUTHORIZED.value(),
                Result.fail(ResultCode.UNAUTHORIZED));
    }
}

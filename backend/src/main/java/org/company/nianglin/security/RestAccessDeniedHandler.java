package org.company.nianglin.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.company.nianglin.common.Result;
import org.company.nianglin.common.ResultCode;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 已登录但无权限：返回 HTTP 403 + 统一响应结构。
 *
 * @author 银龄伴诊团队
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RestAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    @Override
    public void handle(HttpServletRequest request,
                       HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        log.warn("越权访问被拦截 | {} {} | {}", request.getMethod(), request.getRequestURI(),
                accessDeniedException.getMessage());
        SecurityJsonWriter.write(response, objectMapper, HttpStatus.FORBIDDEN.value(),
                Result.fail(ResultCode.FORBIDDEN));
    }
}

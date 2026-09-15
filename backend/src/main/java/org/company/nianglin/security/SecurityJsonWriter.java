package org.company.nianglin.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.company.nianglin.common.Result;
import org.springframework.http.MediaType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 把 {@link Result} 以 JSON 形式写回响应体。
 *
 * <p>Spring Security 的认证 / 授权失败发生在 {@code DispatcherServlet} <b>之前</b>，
 * 走不到 {@code @RestControllerAdvice}，因此必须在这里手工保证响应体结构
 * 与业务接口完全一致，否则前端拦截器会拿到 HTML 错误页而无法解析。</p>
 *
 * @author 银龄伴诊团队
 */
final class SecurityJsonWriter {

    private SecurityJsonWriter() {
    }

    static void write(HttpServletResponse response, ObjectMapper objectMapper, int httpStatus, Result<?> body)
            throws IOException {
        // 响应可能已被上游部分写入，这里直接重置缓冲区，避免出现脏内容拼接
        if (response.isCommitted()) {
            return;
        }
        response.resetBuffer();
        response.setStatus(httpStatus);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(objectMapper.writeValueAsString(body));
        response.getWriter().flush();
    }
}

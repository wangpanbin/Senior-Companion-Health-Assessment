package org.company.nianglin.util;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 当前请求信息读取工具（供管理员操作日志使用）。
 *
 * <h3>IP 为什么要取 {@code X-Forwarded-For} 的第一段</h3>
 *
 * <p>部署在 Nginx / 网关之后时，{@code remoteAddr} 拿到的是反向代理的地址，
 * 几十条日志全是同一个 IP，审计价值为零。{@code X-Forwarded-For} 的第一段
 * 才是原始客户端地址 —— 它是客户端可伪造的，因此<b>只用于审计展示，
 * 绝不可作为安全判定依据</b>（例如不能拿它做封禁或限流）。</p>
 *
 * <h3>为什么不用 {@code @RequestContextHolder} 直接注入到 Controller</h3>
 *
 * <p>操作日志由 Service 写入（事务内），IP 与请求路径必须跟着日志一起落库。
 * 让 Controller 把 IP 当参数一层层传下去，会给每个管理端接口都加两个参数，
 * 而这两个参数与业务毫无关系。这里统一在写入点读取，代价只有一次方法调用。</p>
 *
 * @author 银龄伴诊团队
 * @since M9
 */
public final class RequestInfoUtil {

    /** {@code X-Forwarded-For} 请求头 */
    private static final String HEADER_FORWARDED_FOR = "X-Forwarded-For";

    /** 本机回环地址 */
    private static final String LOCALHOST_V4 = "0:0:0:0:0:0:0:1";

    private RequestInfoUtil() {
    }

    /** 当前请求；不在请求线程内（如定时任务）返回 {@code null} */
    public static HttpServletRequest currentRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            return attributes.getRequest();
        }
        return null;
    }

    /** 客户端 IP；取不到返回 {@code null} */
    public static String clientIp() {
        HttpServletRequest request = currentRequest();
        if (request == null) {
            return null;
        }
        String forwarded = request.getHeader(HEADER_FORWARDED_FOR);
        if (StringUtils.hasText(forwarded)) {
            // 多级代理时形如 "client, proxy1, proxy2"，第一段才是客户端
            String first = forwarded.split(",")[0].trim();
            if (StringUtils.hasText(first)) {
                return first;
            }
        }
        String remote = request.getRemoteAddr();
        return LOCALHOST_V4.equals(remote) ? "127.0.0.1" : remote;
    }

    /** 当前请求路径；取不到返回 {@code null} */
    public static String requestUrl() {
        HttpServletRequest request = currentRequest();
        return request == null ? null : request.getRequestURI();
    }

    /** 当前请求方法；取不到返回 {@code null} */
    public static String requestMethod() {
        HttpServletRequest request = currentRequest();
        return request == null ? null : request.getMethod();
    }
}

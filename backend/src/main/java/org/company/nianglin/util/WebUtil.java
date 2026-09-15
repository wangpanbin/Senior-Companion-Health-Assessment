package org.company.nianglin.util;

import jakarta.servlet.http.HttpServletRequest;
import org.company.nianglin.common.ClientInfo;

/**
 * Web 请求信息提取工具。
 *
 * @author 银龄伴诊团队
 */
public final class WebUtil {

    /** 取不到 IP 时的兜底值 */
    private static final String UNKNOWN = "unknown";

    /** 数据库 {@code sys_login_log.ip} 为 VARCHAR(50)，超长必须截断，否则插入报错 */
    private static final int IP_MAX_LENGTH = 50;

    /** UA 字段为 VARCHAR(255) */
    private static final int UA_MAX_LENGTH = 255;

    /**
     * 反向代理场景下真实 IP 所在的请求头，按优先级排列。
     *
     * <p>一期部署在单机 Nginx 之后，取 {@code X-Forwarded-For} 的第一个地址即可。
     * ⚠️ 这些头可被客户端伪造，因此<b>只用于审计记录，绝不能用于鉴权或限流决策</b>。</p>
     */
    private static final String[] IP_HEADERS = {
            "X-Forwarded-For",
            "X-Real-IP",
            "Proxy-Client-IP",
            "WL-Proxy-Client-IP"
    };

    private WebUtil() {
    }

    /** 提取客户端信息 */
    public static ClientInfo clientInfo(HttpServletRequest request) {
        if (request == null) {
            return ClientInfo.unknown();
        }
        return new ClientInfo(ip(request), truncate(request.getHeader("User-Agent"), UA_MAX_LENGTH));
    }

    /** 提取客户端 IP */
    public static String ip(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        for (String header : IP_HEADERS) {
            String value = request.getHeader(header);
            if (value != null && !value.isBlank() && !UNKNOWN.equalsIgnoreCase(value)) {
                // X-Forwarded-For 形如 "客户端IP, 代理1, 代理2"，只取最左侧
                int comma = value.indexOf(',');
                String ip = (comma > 0 ? value.substring(0, comma) : value).trim();
                if (!ip.isEmpty()) {
                    return truncate(ip, IP_MAX_LENGTH);
                }
            }
        }
        return truncate(request.getRemoteAddr(), IP_MAX_LENGTH);
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}

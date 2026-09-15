package org.company.nianglin.common;

/**
 * 客户端信息（登录日志用）。
 *
 * <p>单独抽出来的原因：Service 层不应该依赖 {@code HttpServletRequest}，
 * 否则单测必须造 Servlet 容器，Mockito 也测不动。把需要的信息在
 * Controller 边界处提出来，Service 就变成纯函数式入参。</p>
 *
 * @param ip        客户端 IP（已考虑反向代理的 {@code X-Forwarded-For}）
 * @param userAgent 浏览器 UA，可能为 {@code null}
 * @author 银龄伴诊团队
 */
public record ClientInfo(String ip, String userAgent) {

    /** 兜底：拿不到请求上下文时使用 */
    public static ClientInfo unknown() {
        return new ClientInfo(null, null);
    }
}

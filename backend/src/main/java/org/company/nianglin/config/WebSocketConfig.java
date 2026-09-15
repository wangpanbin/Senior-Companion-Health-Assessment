package org.company.nianglin.config;

import lombok.RequiredArgsConstructor;
import org.company.nianglin.websocket.JwtHandshakeInterceptor;
import org.company.nianglin.websocket.OrderProgressHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * WebSocket 配置（M5 陪诊实时进度）。
 *
 * <p>端点约定见 {@code docs/api/04-companion-execution.md} §6：
 * {@code WS /ws/progress?token=<accessToken>&orderId=<orderId>}。</p>
 *
 * <h3>为什么这里是裸 WebSocket 而不是 STOMP</h3>
 *
 * <p>{@code PLAN_BACKEND.md} §3 把 M5 定为 STOMP、M8 定为 SSE、并强调「不混用」。
 * 实际落地时 M5 选用了裸 WebSocket + 文本帧，理由是这条通道的能力需求已经被
 * 一张按订单分组的会话表完全覆盖（见 {@code OrderProgressHub} 的类注释），
 * 而 STOMP 会额外引入订阅目的地、Broker 与帧解析三层概念。
 * 与 M8 的分工保持不变：<b>M5 双向（打卡回执 + 进度下行）、M8 单向 SSE</b>，
 * 两条通道仍然互不重叠。决策过程落在 {@code docs/adr/0001-m5-websocket-auth.md}。</p>
 *
 * <h3>握手鉴权在拦截器里，不在这里</h3>
 *
 * <p>{@code addInterceptors} 注册的 {@link JwtHandshakeInterceptor} 会在
 * HTTP 升级为 WebSocket 之前完成「验令牌 + 判订单归属」，
 * 任一不通过则握手失败，连接从未建立。</p>
 *
 * @author 银龄伴诊团队
 * @since M5
 */
@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {

    private final OrderProgressHandler orderProgressHandler;
    private final JwtHandshakeInterceptor jwtHandshakeInterceptor;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(orderProgressHandler, "/ws/progress")
                // allowedOriginPatterns 而不是 setAllowedOrigins("*")：
                // 后者在 Spring 5.3+ 与 allowCredentials 同时出现时会被拒绝。
                // 生产环境由 Nginx 同源转发，这里放宽只是为了让 5173 的前端联调方便
                .addInterceptors(jwtHandshakeInterceptor)
                .setAllowedOriginPatterns("*");
    }
}

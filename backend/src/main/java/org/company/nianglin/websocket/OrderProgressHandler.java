package org.company.nianglin.websocket;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Map;

/**
 * 陪诊进度 WebSocket 处理器（{@code /ws/progress}）。
 *
 * <p>对应 {@code docs/api/04-companion-execution.md} §6。</p>
 *
 * <h3>职责刻意做窄</h3>
 *
 * <p>鉴权与订单归属校验都不在这里，而在 {@link JwtHandshakeInterceptor} ——
 * 握手阶段拒绝掉的连接根本不会进入本处理器，也就不会占用会话资源。
 * 这里只做三件事：登记订阅、回心跳、注销订阅。</p>
 *
 * <h3>心跳是客户端驱动的</h3>
 *
 * <p>约定客户端每 30 秒发一次 {@code ping}，服务端回 {@code pong}。
 * 反过来由服务端定时发心跳的话，得为每个会话挂一个定时器，
 * 而客户端本来就有重连逻辑（指数退避），让客户端被动地「有理由超时断开」
 * 是更省资源也更简单的一侧。服务端这边只要保证回得快。</p>
 *
 * @author 银龄伴诊团队
 * @since M5
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderProgressHandler extends TextWebSocketHandler {

    /** 握手阶段写入的属性名：订阅的订单 ID */
    public static final String ATTR_ORDER_ID = "orderId";

    /** 握手阶段写入的属性名：订阅者用户 ID */
    public static final String ATTR_USER_ID = "userId";

    private static final String PING = "ping";

    private final OrderProgressHub hub;

    @Override
    public void afterConnectionEstablished(@NonNull WebSocketSession session) {
        Long orderId = orderIdOf(session);
        if (orderId == null) {
            // 理论上不会发生：没有 orderId 的握手在拦截器阶段就被拒了。
            // 万一将来有人改了拦截器，这里兜住，避免 NPE 变成一个说不清的错误
            log.warn("进度订阅缺少 orderId，连接将被关闭 | sessionId={}", session.getId());
            closeQuietly(session, CloseStatus.BAD_DATA);
            return;
        }
        hub.subscribe(orderId, session);
        hub.sendTo(session, OrderProgressHub.TYPE_CONNECTED, Map.of("orderId", orderId));
        log.info("进度订阅建立 | orderId={} | userId={} | sessionId={}",
                orderId, session.getAttributes().get(ATTR_USER_ID), session.getId());
    }

    @Override
    protected void handleTextMessage(@NonNull WebSocketSession session, @NonNull TextMessage message) {
        String payload = message.getPayload();
        if (PING.equalsIgnoreCase(payload == null ? "" : payload.trim())) {
            hub.sendTo(session, OrderProgressHub.TYPE_PONG, Map.of());
            return;
        }
        // 客户端不该往上发业务指令（打卡走 REST），收到就记一条 debug，
        // 不报错也不回声，避免把这条通道变成隐形的第二套写接口
        log.debug("进度通道收到非心跳消息，已忽略 | sessionId={} | len={}",
                session.getId(), payload == null ? 0 : payload.length());
    }

    @Override
    public void afterConnectionClosed(@NonNull WebSocketSession session, @NonNull CloseStatus status) {
        Long orderId = orderIdOf(session);
        if (orderId != null) {
            hub.unsubscribe(orderId, session.getId());
        }
        log.debug("进度订阅关闭 | orderId={} | sessionId={} | status={}", orderId, session.getId(), status);
    }

    @Override
    public void handleTransportError(@NonNull WebSocketSession session, @NonNull Throwable exception) {
        log.warn("进度通道传输异常 | sessionId={} | {}", session.getId(), exception.getMessage());
        closeQuietly(session, CloseStatus.SERVER_ERROR);
    }

    private static Long orderIdOf(WebSocketSession session) {
        Object value = session.getAttributes().get(ATTR_ORDER_ID);
        return value instanceof Long id ? id : null;
    }

    private static void closeQuietly(WebSocketSession session, CloseStatus status) {
        try {
            session.close(status);
        } catch (Exception e) {
            log.debug("关闭会话失败（可忽略） | sessionId={} | {}", session.getId(), e.getMessage());
        }
    }
}

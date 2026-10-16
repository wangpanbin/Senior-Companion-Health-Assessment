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

@Slf4j
@Component
@RequiredArgsConstructor
public class ChatWebSocketHandler extends TextWebSocketHandler {
    private final ChatMessageHub hub;

    @Override
    public void afterConnectionEstablished(@NonNull WebSocketSession session) {
        Long orderId = (Long) session.getAttributes().get(WsAttributes.ORDER_ID);
        hub.subscribe(orderId, session);
        hub.sendTo(session, ChatMessageHub.TYPE_CONNECTED, Map.of(
                "orderId", orderId,
                "userId", session.getAttributes().get(WsAttributes.USER_ID),
                "role", session.getAttributes().get(WsAttributes.ROLE)));
    }

    @Override
    protected void handleTextMessage(@NonNull WebSocketSession session, @NonNull TextMessage message) {
        if ("ping".equals(message.getPayload())) {
            hub.sendTo(session, ChatMessageHub.TYPE_PONG, Map.of());
        }
    }

    @Override
    public void afterConnectionClosed(@NonNull WebSocketSession session, @NonNull CloseStatus status) {
        hub.unsubscribe((Long) session.getAttributes().get(WsAttributes.ORDER_ID), session.getId());
    }

    @Override
    public void handleTransportError(@NonNull WebSocketSession session, @NonNull Throwable exception) {
        hub.unsubscribe((Long) session.getAttributes().get(WsAttributes.ORDER_ID), session.getId());
        try {
            session.close(CloseStatus.SERVER_ERROR);
        } catch (Exception ex) {
            log.debug("聊天连接已无法关闭 | sessionId={}", session.getId());
        }
    }
}

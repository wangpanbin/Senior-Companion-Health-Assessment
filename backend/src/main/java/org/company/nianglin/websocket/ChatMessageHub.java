package org.company.nianglin.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class ChatMessageHub {
    public static final String TYPE_CONNECTED = "CONNECTED";
    public static final String TYPE_PONG = "PONG";
    public static final String TYPE_CHAT_MESSAGE = "CHAT_MESSAGE";
    private static final DateTimeFormatter PUSH_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final ConcurrentHashMap<Long, Map<String, WebSocketSession>> groups = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;

    public void subscribe(Long orderId, WebSocketSession session) {
        groups.compute(orderId, (key, group) -> {
            Map<String, WebSocketSession> sessions = group == null ? new ConcurrentHashMap<>() : group;
            sessions.put(session.getId(), session);
            return sessions;
        });
    }

    public void unsubscribe(Long orderId, String sessionId) {
        groups.computeIfPresent(orderId, (key, group) -> {
            group.remove(sessionId);
            return group.isEmpty() ? null : group;
        });
    }

    public int publishToChat(Long orderId, Long excludeUserId, String type, Object data) {
        Map<String, WebSocketSession> group = groups.get(orderId);
        if (group == null) return 0;
        String payload = buildPayload(orderId, type, data);
        if (payload == null) return 0;
        int sent = 0;
        for (WebSocketSession session : group.values()) {
            if (!session.isOpen()) {
                unsubscribe(orderId, session.getId());
            } else if (!Objects.equals(excludeUserId, session.getAttributes().get(WsAttributes.USER_ID))
                    && send(orderId, session, payload)) {
                sent++;
            }
        }
        return sent;
    }

    public void sendTo(WebSocketSession session, String type, Object data) {
        Long orderId = (Long) session.getAttributes().get(WsAttributes.ORDER_ID);
        String payload = buildPayload(orderId, type, data);
        if (payload != null) send(orderId, session, payload);
    }

    public int subscriberCount(Long orderId) {
        Map<String, WebSocketSession> group = groups.get(orderId);
        return group == null ? 0 : group.size();
    }

    private boolean send(Long orderId, WebSocketSession session, String payload) {
        try {
            synchronized (session) {
                if (session.isOpen()) {
                    session.sendMessage(new TextMessage(payload));
                    return true;
                }
            }
        } catch (Exception ex) {
            log.warn("聊天推送失败 | orderId={} | sessionId={}", orderId, session.getId());
        }
        unsubscribe(orderId, session.getId());
        try {
            session.close(CloseStatus.SERVER_ERROR);
        } catch (Exception ex) {
            log.debug("聊天连接已无法关闭 | sessionId={}", session.getId());
        }
        return false;
    }

    private String buildPayload(Long orderId, String type, Object data) {
        try {
            Map<String, Object> payload = new HashMap<>(16);
            if (data instanceof Map<?, ?> map) {
                map.forEach((key, value) -> payload.put(String.valueOf(key), value));
            } else if (data != null) {
                objectMapper.convertValue(data, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {})
                        .forEach(payload::put);
            }
            payload.put("pushTime", LocalDateTime.now().format(PUSH_TIME_FORMAT));
            return objectMapper.writeValueAsString(Map.of("type", type, "orderId", orderId, "data", payload));
        } catch (Exception ex) {
            log.warn("聊天事件序列化失败 | orderId={} | type={}", orderId, type);
            return null;
        }
    }
}

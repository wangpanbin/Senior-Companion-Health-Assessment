package org.company.nianglin.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("聊天推送：订单隔离、发送方排除与死连接清理")
class ChatMessageHubTest {
    private final ObjectMapper json = new ObjectMapper();
    private final ChatMessageHub hub = new ChatMessageHub(json);

    @Test
    void publishShouldIsolateOrdersAndExcludeAllSenderSessions() throws Exception {
        WebSocketSession sender = session("sender", 11L, 101L);
        WebSocketSession otherTab = session("sender-tab", 11L, 101L);
        WebSocketSession receiver = session("receiver", 22L, 101L);
        WebSocketSession outsider = session("outsider", 33L, 102L);
        hub.subscribe(101L, sender);
        hub.subscribe(101L, otherTab);
        hub.subscribe(101L, receiver);
        hub.subscribe(102L, outsider);
        assertEquals(1, hub.publishToChat(101L, 11L, "CHAT_MESSAGE", Map.of("messageId", 900L)));
        verify(sender, never()).sendMessage(any());
        verify(otherTab, never()).sendMessage(any());
        verify(outsider, never()).sendMessage(any());
        ArgumentCaptor<TextMessage> sent = ArgumentCaptor.forClass(TextMessage.class);
        verify(receiver).sendMessage(sent.capture());
        var frame = json.readTree(sent.getValue().getPayload());
        assertEquals(101, frame.path("orderId").asLong());
        assertEquals(900, frame.at("/data/messageId").asLong());
        assertFalse(frame.at("/data/pushTime").asText().isBlank());
    }

    @Test
    void publishShouldRemoveClosedAndFailedSessions() throws Exception {
        WebSocketSession closed = session("closed", 22L, 101L);
        when(closed.isOpen()).thenReturn(false);
        WebSocketSession failed = session("failed", 22L, 101L);
        doThrow(new IOException("closed")).when(failed).sendMessage(any());
        WebSocketSession healthy = session("healthy", 22L, 101L);
        hub.subscribe(101L, closed);
        hub.subscribe(101L, failed);
        hub.subscribe(101L, healthy);
        assertEquals(1, hub.publishToChat(101L, 11L, "CHAT_MESSAGE", Map.of()));
        assertEquals(1, hub.subscriberCount(101L));
        verify(healthy).sendMessage(any(TextMessage.class));
        hub.unsubscribe(101L, healthy.getId());
        assertEquals(0, hub.subscriberCount(101L));
    }

    @Test
    void sendToShouldIncludeOrderAndClearFailedSubscription() throws Exception {
        WebSocketSession receiver = session("receiver", 22L, 101L);
        hub.subscribe(101L, receiver);
        hub.sendTo(receiver, "PONG", Map.of());
        ArgumentCaptor<TextMessage> sent = ArgumentCaptor.forClass(TextMessage.class);
        verify(receiver).sendMessage(sent.capture());
        assertEquals(101L, json.readTree(sent.getValue().getPayload()).path("orderId").asLong());
        doThrow(new IOException("closed")).when(receiver).sendMessage(any());
        hub.sendTo(receiver, "PONG", Map.of());
        assertEquals(0, hub.subscriberCount(101L));
    }

    static WebSocketSession session(String id, long userId, long orderId) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        when(session.isOpen()).thenReturn(true);
        when(session.getAttributes()).thenReturn(Map.of(WsAttributes.USER_ID, userId,
                WsAttributes.ORDER_ID, orderId, WsAttributes.ROLE, "FAMILY"));
        return session;
    }
}

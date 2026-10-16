package org.company.nianglin.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("聊天通道：首帧、心跳、禁止上行写入与注销")
class ChatWebSocketHandlerTest {
    private final ObjectMapper json = new ObjectMapper();
    private final ChatMessageHub hub = new ChatMessageHub(json);
    private final ChatWebSocketHandler handler = new ChatWebSocketHandler(hub);

    @Test
    void connectionShouldAcknowledgeAndOnlyAnswerBarePing() throws Exception {
        WebSocketSession session = ChatMessageHubTest.session("one", 11L, 101L);
        handler.afterConnectionEstablished(session);
        assertEquals(1, hub.subscriberCount(101L));
        handler.handleTextMessage(session, new TextMessage("ping"));
        handler.handleTextMessage(session, new TextMessage("{\"msgType\":\"TEXT\",\"content\":\"ignored\"}"));
        handler.handleTextMessage(session, new TextMessage(" ping "));
        ArgumentCaptor<TextMessage> frames = ArgumentCaptor.forClass(TextMessage.class);
        verify(session, times(2)).sendMessage(frames.capture());
        List<TextMessage> sent = frames.getAllValues();
        assertEquals("CONNECTED", json.readTree(sent.get(0).getPayload()).path("type").asText());
        assertEquals(11L, json.readTree(sent.get(0).getPayload()).at("/data/userId").asLong());
        assertEquals("PONG", json.readTree(sent.get(1).getPayload()).path("type").asText());
        handler.afterConnectionClosed(session, CloseStatus.NORMAL);
        assertEquals(0, hub.subscriberCount(101L));
    }

    @Test
    void transportErrorShouldUnsubscribeBeforeCloseCallback() throws Exception {
        WebSocketSession session = ChatMessageHubTest.session("one", 11L, 101L);
        handler.afterConnectionEstablished(session);
        handler.handleTransportError(session, new IllegalStateException("closed"));
        assertEquals(0, hub.subscriberCount(101L));
        verify(session).close(CloseStatus.SERVER_ERROR);
    }
}

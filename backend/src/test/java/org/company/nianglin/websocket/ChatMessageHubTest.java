package org.company.nianglin.websocket;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import org.company.nianglin.config.JacksonConfig;
import org.company.nianglin.vo.ChatMessageVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("聊天推送：订单隔离、发送方排除与死连接清理")
class ChatMessageHubTest {
    private final ObjectMapper json = new ObjectMapper();
    private final ChatMessageHub hub = new ChatMessageHub(json);
    private final ChatMessageHub pojoHub = new ChatMessageHub(productionLikeJson());

    /**
     * 与生产 ObjectMapper 等价：LocalDateTime 用全局格式 + non_null。
     * 直接用上面那个 new ObjectMapper() 会让 convertValue 抛异常、buildPayload 静默返回 null，
     * 测试就「绿着什么都不推」，等于没测。
     */
    private static ObjectMapper productionLikeJson() {
        SimpleModule dateTimeModule = new SimpleModule()
                .addSerializer(LocalDateTime.class, new LocalDateTimeSerializer(
                        DateTimeFormatter.ofPattern(JacksonConfig.DATE_TIME_PATTERN)));
        return JsonMapper.builder()
                .addModule(dateTimeModule)
                .defaultPropertyInclusion(
                        JsonInclude.Value.construct(JsonInclude.Include.NON_NULL, JsonInclude.Include.NON_NULL))
                .build();
    }

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

    @Test
    @DisplayName("POJO 载荷展开成字段表，时间按全局格式")
    void pojoPayloadShouldFlattenToFieldMap() throws Exception {
        WebSocketSession receiver = session("receiver", 22L, 101L);
        pojoHub.subscribe(101L, receiver);
        ChatMessageVO vo = new ChatMessageVO().setMessageId(900L).setOrderId(101L).setSenderId(11L)
                .setSenderRole("FAMILY").setMsgType("TEXT").setMsgTypeLabel("文字")
                .setContent("已到医院").setFileId("f_1").setFileUrl("/uploads/a.jpg")
                .setFileSize(2048L).setCreateTime(LocalDateTime.of(2026, 10, 16, 10, 0));
        assertEquals(1, pojoHub.publishToChat(101L, 11L, "CHAT_MESSAGE", vo));
        ArgumentCaptor<TextMessage> sent = ArgumentCaptor.forClass(TextMessage.class);
        verify(receiver).sendMessage(sent.capture());
        var frame = json.readTree(sent.getValue().getPayload());
        assertEquals(900, frame.at("/data/messageId").asLong());
        assertEquals("TEXT", frame.at("/data/msgType").asText());
        assertEquals("2026-10-16 10:00:00", frame.at("/data/createTime").asText());
        assertFalse(frame.at("/data/pushTime").asText().isBlank());
    }

    @Test
    @DisplayName("显式 null 字段不进帧（与 REST 的 non_null 口径一致）")
    void nullFieldsShouldBeAbsentFromFrame() throws Exception {
        WebSocketSession receiver = session("receiver", 22L, 101L);
        pojoHub.subscribe(101L, receiver);
        Map<String, Object> withNulls = new HashMap<>(8);
        withNulls.put("messageId", 900L);
        withNulls.put("fileId", null);
        withNulls.put("content", null);
        pojoHub.publishToChat(101L, 11L, "CHAT_MESSAGE", withNulls);
        ArgumentCaptor<TextMessage> sent = ArgumentCaptor.forClass(TextMessage.class);
        verify(receiver).sendMessage(sent.capture());
        var frame = json.readTree(sent.getValue().getPayload());
        assertEquals(900, frame.at("/data/messageId").asLong());
        assertTrue(frame.at("/data/fileId").isMissingNode());
        assertTrue(frame.at("/data/content").isMissingNode());
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
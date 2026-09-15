package org.company.nianglin.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 进度推送中心单测（M5）。
 *
 * <p>这里验证的是<b>「按订单分组」这条不能被破坏的性质</b>：
 * 订单 A 的订阅者绝不能收到订单 B 的事件。少一个 {@code orderId} 条件，
 * 推送就变成了全员广播，而家属端看到的会是别人家老人的就诊进度 ——
 * 这类 bug 在功能测试里完全看不出来（页面「确实有进度在动」）。</p>
 *
 * @author 银龄伴诊团队
 * @since M5
 */
@DisplayName("进度推送：按订单分组 / 断开清理 / 心跳")
class OrderProgressHubTest {

    private final OrderProgressHub hub = new OrderProgressHub(new ObjectMapper());

    @Test
    @DisplayName("只推给订阅了该订单的会话，不串单")
    void publishOnlyReachesSubscribersOfThatOrder() throws Exception {
        WebSocketSession sessionA = session("A");
        WebSocketSession sessionB = session("B");
        hub.subscribe(1L, sessionA);
        hub.subscribe(2L, sessionB);

        hub.publish(1L, OrderProgressHub.TYPE_ORDER_PROGRESS, Map.of("node", "ARRIVE"));

        verify(sessionA).sendMessage(any(TextMessage.class));
        verify(sessionB, never()).sendMessage(any(TextMessage.class));
        assertEquals(1, hub.subscriberCount(1L));
    }

    @Test
    @DisplayName("报文结构为 {type, orderId, data.pushTime}，与接口文档 §6 对齐")
    void payloadShape() throws Exception {
        WebSocketSession session = session("A");
        hub.subscribe(7L, session);

        hub.publish(7L, OrderProgressHub.TYPE_ORDER_PROGRESS, Map.of("node", "ARRIVE", "nodeLabel", "到院"));

        org.mockito.ArgumentCaptor<TextMessage> captor =
                org.mockito.ArgumentCaptor.forClass(TextMessage.class);
        verify(session).sendMessage(captor.capture());
        Map<?, ?> body = new ObjectMapper().readValue(captor.getValue().getPayload(), Map.class);

        assertEquals(OrderProgressHub.TYPE_ORDER_PROGRESS, body.get("type"));
        assertEquals(7, body.get("orderId"));
        Map<?, ?> data = (Map<?, ?>) body.get("data");
        assertEquals("ARRIVE", data.get("node"));
        assertTrue(data.containsKey("pushTime"), "pushTime 必须由服务端盖章");
    }

    @Test
    @DisplayName("会话摘除后不再收到推送；分组空了自动回收")
    void unsubscribeStopsDelivery() throws Exception {
        WebSocketSession session = session("A");
        hub.subscribe(1L, session);
        hub.unsubscribe(1L, "A");

        hub.publish(1L, OrderProgressHub.TYPE_ORDER_PROGRESS, Map.of());

        verify(session, never()).sendMessage(any(TextMessage.class));
        assertEquals(0, hub.subscriberCount(1L));
    }

    @Test
    @DisplayName("没有订阅者时推送静默返回，不抛异常")
    void publishWithoutSubscribersIsNoop() {
        hub.publish(99L, OrderProgressHub.TYPE_ORDER_PROGRESS, Map.of("node", "ARRIVE"));
        assertEquals(0, hub.subscriberCount(99L));
    }

    @Test
    @DisplayName("已关闭的会话被跳过，不影响同一组内的其他会话")
    void closedSessionIsSkipped() throws Exception {
        WebSocketSession closed = session("CLOSED");
        given(closed.isOpen()).willReturn(false);
        WebSocketSession alive = session("ALIVE");
        hub.subscribe(1L, closed);
        hub.subscribe(1L, alive);

        hub.publish(1L, OrderProgressHub.TYPE_ORDER_PROGRESS, Map.of());

        verify(closed, never()).sendMessage(any(TextMessage.class));
        verify(alive).sendMessage(any(TextMessage.class));
    }

    private static WebSocketSession session(String id) {
        WebSocketSession session = mock(WebSocketSession.class);
        given(session.getId()).willReturn(id);
        given(session.isOpen()).willReturn(true);
        given(session.getAttributes()).willReturn(new HashMap<>());
        return session;
    }
}

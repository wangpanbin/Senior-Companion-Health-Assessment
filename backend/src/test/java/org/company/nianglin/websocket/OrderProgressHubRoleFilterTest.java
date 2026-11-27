package org.company.nianglin.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.company.nianglin.constant.RoleConstants;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 进度推送中心的按角色过滤单测（ADR-0022）。
 *
 * <p>要守的性质是：<b>位置事件不得发到老人与管理员的会话上</b>。</p>
 *
 * <p>这条泄漏在功能测试里完全看不出来 —— 家属端一切正常，只有抓包 / 看 WS 帧
 * 才会发现管理员那台电脑也在收「陪诊员此刻在哪」。而位置是一个真实工作者的
 * 行踪信息，泄漏一次就是一次隐私事故，不是「多推了一条无关消息」。</p>
 *
 * <p>同时钉住反向性质：{@code ORDER_PROGRESS} 必须继续对全部相关方广播 ——
 * 收窄位置事件的可见范围，不能顺手把打卡进度也收窄了。</p>
 *
 * @author 银龄伴诊团队
 * @since ADR-0022
 */
@DisplayName("进度推送：位置事件按角色过滤 / 进度事件仍全广播")
class OrderProgressHubRoleFilterTest {

    private final OrderProgressHub hub = new OrderProgressHub(new ObjectMapper());

    @Test
    @DisplayName("LOCATION 只发家属与陪诊员，老人与管理员收不到")
    void locationOnlyReachesFamilyAndCompanion() throws Exception {
        WebSocketSession family = session("F", RoleConstants.FAMILY);
        WebSocketSession companion = session("C", RoleConstants.COMPANION);
        WebSocketSession elder = session("E", RoleConstants.ELDER);
        WebSocketSession admin = session("A", RoleConstants.ADMIN);
        hub.subscribe(1001L, family);
        hub.subscribe(1001L, companion);
        hub.subscribe(1001L, elder);
        hub.subscribe(1001L, admin);

        hub.publishTo(1001L, OrderProgressHub.TYPE_LOCATION,
                Map.of("distanceMeters", 1200), OrderProgressHub.LOCATION_AUDIENCE);

        verify(family).sendMessage(any(TextMessage.class));
        verify(companion).sendMessage(any(TextMessage.class));
        verify(elder, never()).sendMessage(any(TextMessage.class));
        verify(admin, never()).sendMessage(any(TextMessage.class));
    }

    @Test
    @DisplayName("LOCATION_PAUSED 同样只发家属与陪诊员")
    void locationPausedOnlyReachesFamilyAndCompanion() throws Exception {
        WebSocketSession family = session("F", RoleConstants.FAMILY);
        WebSocketSession admin = session("A", RoleConstants.ADMIN);
        hub.subscribe(2001L, family);
        hub.subscribe(2001L, admin);

        hub.publishTo(2001L, OrderProgressHub.TYPE_LOCATION_PAUSED,
                Map.of("sharingEnabled", false), OrderProgressHub.LOCATION_AUDIENCE);

        verify(family).sendMessage(any(TextMessage.class));
        verify(admin, never()).sendMessage(any(TextMessage.class));
    }

    @Test
    @DisplayName("ORDER_PROGRESS 仍对全部相关方广播（收窄位置范围不得波及打卡进度）")
    void orderProgressStillBroadcastsToEveryone() throws Exception {
        WebSocketSession elder = session("E", RoleConstants.ELDER);
        WebSocketSession admin = session("A", RoleConstants.ADMIN);
        hub.subscribe(3001L, elder);
        hub.subscribe(3001L, admin);

        hub.publish(3001L, OrderProgressHub.TYPE_ORDER_PROGRESS, Map.of("node", "ARRIVE"));

        verify(elder).sendMessage(any(TextMessage.class));
        verify(admin).sendMessage(any(TextMessage.class));
    }

    @Test
    @DisplayName("会话缺 role 属性时不被任何白名单命中（失败即不发，而不是失败即全发）")
    void sessionWithoutRoleIsNotSentTo() throws Exception {
        WebSocketSession anonymous = session("X", null);
        hub.subscribe(4001L, anonymous);

        hub.publishTo(4001L, OrderProgressHub.TYPE_LOCATION,
                Map.of("distanceMeters", 100), OrderProgressHub.LOCATION_AUDIENCE);

        verify(anonymous, never()).sendMessage(any(TextMessage.class));
    }

    @Test
    @DisplayName("roles 传 null 等价于全广播（publish 的语义不变）")
    void nullRolesMeansBroadcast() throws Exception {
        WebSocketSession admin = session("A", RoleConstants.ADMIN);
        hub.subscribe(5001L, admin);

        hub.publishTo(5001L, OrderProgressHub.TYPE_ORDER_PROGRESS, Map.of(), null);

        verify(admin).sendMessage(any(TextMessage.class));
    }

    @Test
    @DisplayName("可推送的角色集合就是 FAMILY + COMPANION（与 GET /location 的可见性同源）")
    void audienceIsExactlyFamilyAndCompanion() {
        assertEquals(Set.of(RoleConstants.FAMILY, RoleConstants.COMPANION),
                OrderProgressHub.LOCATION_AUDIENCE);
    }

    private static WebSocketSession session(String id, String role) {
        WebSocketSession session = mock(WebSocketSession.class);
        given(session.getId()).willReturn(id);
        given(session.isOpen()).willReturn(true);
        Map<String, Object> attributes = new HashMap<>();
        if (role != null) {
            attributes.put(WsAttributes.ROLE, role);
        }
        given(session.getAttributes()).willReturn(attributes);
        return session;
    }
}

package org.company.nianglin.websocket;

import org.company.nianglin.common.ResultCode;
import org.company.nianglin.constant.WsPathConstants;
import org.company.nianglin.entity.CompanionOrder;
import org.company.nianglin.exception.BusinessException;
import org.company.nianglin.security.JwtTokenProvider;
import org.company.nianglin.security.LoginUser;
import org.company.nianglin.security.TokenPayload;
import org.company.nianglin.security.TokenStore;
import org.company.nianglin.service.OrderService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;

import java.net.URI;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("聊天握手：身份、订单归属与已接单门禁")
class ChatHandshakeTest {
    private final JwtTokenProvider provider = mock(JwtTokenProvider.class);
    private final TokenStore store = mock(TokenStore.class);
    private final OrderService orders = mock(OrderService.class);
    private final JwtHandshakeInterceptor interceptor = new JwtHandshakeInterceptor(provider, store, orders);
    private final ServerHttpResponse response = mock(ServerHttpResponse.class);

    @Test
    void handshakeShouldRejectOtherOrdersWith403() {
        token();
        when(orders.requireInvolved(eq(101L), any(LoginUser.class)))
                .thenThrow(new BusinessException(ResultCode.ORDER_NO_PERMISSION));
        assertFalse(handshake(WsPathConstants.CHAT + "?token=test&orderId=101"));
        verify(response).setStatusCode(HttpStatus.FORBIDDEN);
    }

    @Test
    void handshakeShouldRejectChatWithoutCompanion() {
        token();
        CompanionOrder order = new CompanionOrder();
        order.setId(101L);
        when(orders.requireInvolved(eq(101L), any(LoginUser.class))).thenReturn(order);
        assertFalse(handshake(WsPathConstants.CHAT + "?token=test&orderId=101"));
        verify(response).setStatusCode(HttpStatus.FORBIDDEN);
    }

    @Test
    void handshakeShouldKeepProgressAvailableBeforeAcceptance() {
        token();
        when(orders.requireInvolved(eq(101L), any(LoginUser.class))).thenReturn(new CompanionOrder());
        assertTrue(handshake(WsPathConstants.PROGRESS + "?token=test&orderId=101"));
    }

    @Test
    void handshakeShouldRejectMissingOrRefreshToken() {
        assertFalse(handshake(WsPathConstants.CHAT + "?orderId=101"));
        verify(response).setStatusCode(HttpStatus.UNAUTHORIZED);
        reset(response);
        when(provider.parse("test")).thenReturn(new TokenPayload(11L, "test", "FAMILY", "refresh", "jti", 0, 0, Long.MAX_VALUE));
        assertFalse(handshake(WsPathConstants.CHAT + "?token=test&orderId=101"));
        verify(response).setStatusCode(HttpStatus.UNAUTHORIZED);
        verifyNoInteractions(orders);
    }

    private void token() {
        when(provider.parse("test")).thenReturn(new TokenPayload(11L, "test", "FAMILY", "access", "jti", 0, 0, Long.MAX_VALUE));
    }

    private boolean handshake(String path) {
        ServerHttpRequest request = mock(ServerHttpRequest.class);
        when(request.getURI()).thenReturn(URI.create("http://localhost" + path));
        return interceptor.beforeHandshake(request, response, mock(WebSocketHandler.class), new HashMap<>());
    }
}

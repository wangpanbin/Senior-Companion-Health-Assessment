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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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

    /**
     * 并发握手（最终复盘 BE-04 的后半条：握手从未压测）。
     *
     * <p>要证的不是「吞吐多少」，而是**认证结果不会串**：拦截器是单例，
     * 如果它把「本次请求已认证」写进了实例字段（而不是 {@code attributes}），
     * 并发下就会出现「上一次的合法身份被下一个无令牌请求继承」——
     * 那是一条静默越权，单线程用例永远测不出来。</p>
     *
     * <p>32 个请求、一半带令牌一半不带，要求结果一一对应：16 通过 16 拒绝。
     * 每个请求用**独立的 response mock**，避免断言被多线程共享的 verify 干扰。</p>
     */
    @Test
    @DisplayName("并发握手：合法与非法请求各 16 个，结果必须一一对应、不串状态")
    void concurrentHandshakesShouldNotLeakAuthState() throws Exception {
        token();
        when(orders.requireInvolved(eq(101L), any(LoginUser.class))).thenReturn(new CompanionOrder());

        final int half = 16;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        try {
            for (int i = 0; i < half * 2; i++) {
                final boolean withToken = i % 2 == 0;
                results.add(pool.submit(() -> {
                    start.await();
                    return handshake(WsPathConstants.PROGRESS + "?orderId=101"
                            + (withToken ? "&token=test" : ""));
                }));
            }
            start.countDown();

            int accepted = 0;
            for (Future<Boolean> f : results) {
                if (f.get(10, TimeUnit.SECONDS)) {
                    accepted++;
                }
            }
            assertEquals(half, accepted,
                    "带令牌的必须全部通过、不带令牌的必须全部被拒 —— 出现偏差说明认证状态在请求间串了");
        } finally {
            pool.shutdownNow();
        }
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

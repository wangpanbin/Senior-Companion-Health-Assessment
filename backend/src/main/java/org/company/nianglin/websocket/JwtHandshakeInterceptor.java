package org.company.nianglin.websocket;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.company.nianglin.exception.BusinessException;
import org.company.nianglin.security.JwtTokenProvider;
import org.company.nianglin.security.LoginUser;
import org.company.nianglin.security.TokenPayload;
import org.company.nianglin.security.TokenStore;
import org.company.nianglin.service.OrderService;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Map;

/**
 * WebSocket 握手鉴权（{@code /ws/progress?token=xxx&orderId=1001}）。
 *
 * <p>对应 {@code docs/adr/0001-m5-websocket-auth.md} 的决策：
 * <b>不动 {@code JwtAuthenticationFilter}，把「校验 JWT + 判订单归属」放在握手阶段完成。</b></p>
 *
 * <h3>为什么令牌走 query 而不是 header</h3>
 *
 * <p>浏览器原生的 {@code WebSocket} 构造函数<b>不允许自定义请求头</b>，
 * 前端拿不到写 {@code Authorization} 的机会。备选是
 * 「先连上、再发一帧带令牌的认证消息」，但那意味着服务端要先接受一个未认证的连接，
 * 在认证帧到达之前必须把它当作「未知身份」处理 —— 这段中间态就是漏洞面。
 * 握手阶段直接拒掉，未认证的连接根本不产生会话。</p>
 *
 * <h3>为什么两处都要校验</h3>
 *
 * <p>验令牌只回答「你是谁」，回答不了「这一单跟不跟你有关」。
 * 少掉第二个校验，任何一个登录用户都能订阅别人的陪诊进度 ——
 * 那是老人全天行动轨迹级别的信息。所以这里复用
 * {@link OrderService#requireInvolved(Long, LoginUser)}，
 * 与 REST 接口走同一套归属判定，不另写一套。</p>
 *
 * <h3>没有 SecurityContext 怎么办</h3>
 *
 * <p>握手是一次普通 HTTP 请求，但会话建立后，后续的帧处理运行在别的线程上，
 * {@code SecurityContextHolder} 早就被清空了。所以这里不往
 * {@code SecurityContext} 里塞东西，而是把身份放进
 * {@link org.springframework.web.socket.WebSocketSession#getAttributes() 会话属性}，
 * 由 {@link OrderProgressHandler} 按需读取。</p>
 *
 * @author 银龄伴诊团队
 * @since M5
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtHandshakeInterceptor implements HandshakeInterceptor {

    private static final String PARAM_TOKEN = "token";
    private static final String PARAM_ORDER_ID = "orderId";

    private final JwtTokenProvider tokenProvider;
    private final TokenStore tokenStore;
    private final OrderService orderService;

    @Override
    public boolean beforeHandshake(@NonNull ServerHttpRequest request,
                                   @NonNull ServerHttpResponse response,
                                   @NonNull WebSocketHandler wsHandler,
                                   @NonNull Map<String, Object> attributes) {
        Map<String, String> params = UriComponentsBuilder.fromUri(request.getURI())
                .build()
                .getQueryParams()
                .toSingleValueMap();

        String token = params.get(PARAM_TOKEN);
        Long orderId = parseOrderId(params.get(PARAM_ORDER_ID));
        if (token == null || token.isBlank() || orderId == null) {
            log.warn("进度订阅握手被拒：缺少 token 或 orderId | path={}", request.getURI().getPath());
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }

        try {
            TokenPayload payload = tokenProvider.parse(token);
            // 与 JwtAuthenticationFilter 保持同一套判定顺序：类型 → 黑名单 → 密码版本
            if (!payload.isAccess()) {
                throw new BusinessException(org.company.nianglin.common.ResultCode.TOKEN_INVALID);
            }
            if (tokenStore.isBlacklisted(payload.jti())) {
                throw new BusinessException(org.company.nianglin.common.ResultCode.TOKEN_INVALID);
            }
            if (payload.passwordVersion() != tokenStore.currentPasswordVersion(payload.userId())) {
                throw new BusinessException(org.company.nianglin.common.ResultCode.TOKEN_INVALID);
            }

            LoginUser loginUser = new LoginUser(payload.userId(), payload.username(), payload.role(),
                    payload.passwordVersion(), payload.jti(), payload.expiresAtMillis());

            // 归属校验：非相关方在这里就出不去，不会进到会话注册表
            orderService.requireInvolved(orderId, loginUser);

            attributes.put(OrderProgressHandler.ATTR_ORDER_ID, orderId);
            attributes.put(OrderProgressHandler.ATTR_USER_ID, payload.userId());
            attributes.put("role", payload.role());
            log.info("进度订阅握手通过 | orderId={} | userId={} | role={}",
                    orderId, payload.userId(), payload.role());
            return true;
        } catch (BusinessException e) {
            // 401 与 403 分开回：前端据此决定「去刷新令牌」还是「别白试了」。
            // 注意日志里不打印 token 本身
            boolean forbidden = e.getCode() != null
                    && e.getCode().equals(org.company.nianglin.common.ResultCode.FORBIDDEN.getCode());
            log.warn("进度订阅握手被拒 | orderId={} | code={} | message={}", orderId, e.getCode(), e.getMessage());
            response.setStatusCode(forbidden ? HttpStatus.FORBIDDEN : HttpStatus.UNAUTHORIZED);
            return false;
        } catch (Exception e) {
            log.warn("进度订阅握手异常 | orderId={} | {}", orderId, e.getMessage());
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
    }

    @Override
    public void afterHandshake(@NonNull ServerHttpRequest request,
                               @NonNull ServerHttpResponse response,
                               @NonNull WebSocketHandler wsHandler,
                               Exception exception) {
        if (exception != null) {
            log.warn("进度订阅握手完成后出现异常 | {}", exception.getMessage());
        }
    }

    /** 订单 ID 非数字按「没传」处理，直接拒绝，不做静默兜底 */
    private static Long parseOrderId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

package org.company.nianglin.websocket;

/**
 * WebSocket 握手阶段写入会话属性的 key 常量。
 *
 * <p>{@link JwtHandshakeInterceptor} 验签成功后把订单、用户、角色写进
 * {@code WebSocketSession#getAttributes()}，各 Handler / Hub 按这里的 key 读取。</p>
 *
 * <h3>为什么要抽出来</h3>
 *
 * <p>M5 时只有一条通道，key 放在 {@code OrderProgressHandler} 里没问题；
 * M13 聊天通道复用同一个拦截器后，如果 Hub/Handler 各自定义字面量，
 * 拦截器改一个 key 就会静默弄断另一条通道。单一真源，谁也不准散写。</p>
 *
 * @author 银龄伴诊团队
 * @since M13
 */
public final class WsAttributes {

    private WsAttributes() {
    }

    /** 订阅的订单 ID（Long） */
    public static final String ORDER_ID = "orderId";

    /** 订阅者用户 ID（Long） */
    public static final String USER_ID = "userId";

    /** 订阅者角色（String，RoleConstants 枚举名） */
    public static final String ROLE = "role";
}

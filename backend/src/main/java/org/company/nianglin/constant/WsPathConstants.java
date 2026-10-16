package org.company.nianglin.constant;

/**
 * WebSocket 端点路径常量。
 *
 * <p>注册端（{@code WebSocketConfig}）与握手鉴权（{@code JwtHandshakeInterceptor}）
 * 必须读同一份路径：拦截器按路径判断该不该套用聊天门禁（未接单回 7003），
 * 两处各写一遍字面量时，改注册路径会让门禁静默失效而不报错。</p>
 *
 * <p>前端对应的硬编码在 {@code frontend/src/api/chat.js} 与
 * {@code frontend/src/utils/realtime.js}，契约见
 * {@code docs/api/04-companion-execution.md} §6 与 {@code docs/api/11-order-chat.md} §7。</p>
 *
 * @author 银龄伴诊团队
 * @since M13
 */
public final class WsPathConstants {

    private WsPathConstants() {
    }

    /** M5 陪诊执行进度（打卡回执 + 进度下行，双向） */
    public static final String PROGRESS = "/ws/progress";

    /** M13 订单聊天（家属↔陪诊员实时消息，双向） */
    public static final String CHAT = "/ws/chat";
}

package org.company.nianglin.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.company.nianglin.constant.RoleConstants;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 陪诊进度推送中心（按订单分组）。
 *
 * <p>对应 {@code docs/api/04-companion-execution.md} §6 的实现要点：
 * 「服务端按 {@code orderId} 维护会话分组，推送时只发给该订单的订阅者」。</p>
 *
 * <h3>为什么不直接依赖 Spring 的 STOMP 广播</h3>
 *
 * <p>本项目的推送需求是「一单一组、组内广播」，用 STOMP 需要引入
 * {@code SimpMessagingTemplate}、Broker 配置与订阅目的地约定，
 * 换来的能力（复杂路由、多种消息语义）一个都用不上。
 * 一张 {@code Map<Long, Map<String, WebSocketSession>>} 就把事情说完了，
 * 而且「谁订阅了哪一单」这件事在内存里是可读的，排查问题不需要猜。</p>
 *
 * <h3>并发注意</h3>
 *
 * <p>{@link WebSocketSession} 不是线程安全的：两个打卡几乎同时完成时，
 * 两个线程会往同一个 session 写。这里对 session 加锁串行化发送，
 * 宁可让推送慢几毫秒，也不要产出半截 JSON 让前端的 {@code JSON.parse} 崩掉。</p>
 *
 * @author 银龄伴诊团队
 * @since M5
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderProgressHub {

    /** 心跳响应事件类型 */
    public static final String TYPE_PONG = "PONG";

    /** 连接建立确认事件类型（非文档约定，仅用于前端确认通道可用） */
    public static final String TYPE_CONNECTED = "CONNECTED";

    /** 打卡 / 订单状态变更 */
    public static final String TYPE_ORDER_PROGRESS = "ORDER_PROGRESS";

    /**
     * 陪诊员位置上报（ADR-0022）。
     *
     * <p><b>只发 FAMILY + COMPANION</b>（见 {@link #LOCATION_AUDIENCE}）。
     * 位置是一个真实工作者的行踪信息，管理员与老人看到「打卡到哪了」就够了。</p>
     */
    public static final String TYPE_LOCATION = "LOCATION";

    /**
     * 陪诊员暂停位置共享（ADR-0022）。
     *
     * <p>同样只发 FAMILY + COMPANION。</p>
     *
     * <p><b>为什么暂停要主动推一条，而不是静默停发</b>：
     * 冻住的红点会被家属读成「出事了」。暂停必须是<b>可解释的空白</b>。</p>
     */
    public static final String TYPE_LOCATION_PAUSED = "LOCATION_PAUSED";

    /**
     * 位置类事件的可见角色集合。
     *
     * <p>与 {@code GET /api/execution/{orderId}/location} 的可见性严格一致 ——
     * <b>能收到推送的才能查询，能查询的才能收到推送</b>。
     * 两者一旦分叉，「推了但查不到」会让前端拿不到铺底数据，「查得到但不推」会让
     * 页面永远停在进页面那一刻。</p>
     */
    public static final Set<String> LOCATION_AUDIENCE =
            Set.of(RoleConstants.FAMILY, RoleConstants.COMPANION);

    private static final DateTimeFormatter PUSH_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** orderId → (sessionId → session) */
    private final ConcurrentHashMap<Long, Map<String, WebSocketSession>> groups = new ConcurrentHashMap<>();

    private final ObjectMapper objectMapper;

    /** 把会话登记到某个订单的分组里 */
    public void subscribe(Long orderId, WebSocketSession session) {
        groups.computeIfAbsent(orderId, k -> new ConcurrentHashMap<>()).put(session.getId(), session);
        log.debug("进度订阅已建立 | orderId={} | sessionId={} | 组内会话数={}",
                orderId, session.getId(), groups.get(orderId).size());
    }

    /** 会话关闭时摘除；分组空了就把整组删掉，避免长跑之后留下成片的空 Map */
    public void unsubscribe(Long orderId, String sessionId) {
        Map<String, WebSocketSession> group = groups.get(orderId);
        if (group == null) {
            return;
        }
        group.remove(sessionId);
        if (group.isEmpty()) {
            groups.remove(orderId, group);
        }
        log.debug("进度订阅已断开 | orderId={} | sessionId={}", orderId, sessionId);
    }

    /**
     * 向某个订单的全部订阅者推送一条事件。
     *
     * <p>单发失败只记日志、不向上抛：一次推送失败不该让已经写进库的打卡记录回滚，
     * 家属端还有「重连后拉一次 {@code /progress} 与 {@code /checkins}」这条补齐路径。</p>
     *
     * @param orderId 订单 ID
     * @param type    事件类型，取值见 {@code docs/api/04-companion-execution.md} §6「消息类型」
     * @param data    事件负载，序列化后放在 {@code data} 字段里
     */
    public void publish(Long orderId, String type, Object data) {
        publishTo(orderId, type, data, null);
    }

    /**
     * 按角色过滤地向某个订单的订阅者推送一条事件（ADR-0022）。
     *
     * <p>握手阶段 {@code JwtHandshakeInterceptor} 已把 {@code role} 写进会话属性，
     * 这里直接读，不做二次查询 —— 每个会话都查一次库会把一次广播变成 N 次 SQ。
     * 角色在会话期内不会变（改角色要重新登录，令牌会变）。</p>
     *
     * @param orderId 订单 ID
     * @param type    事件类型
     * @param data    事件负载
     * @param roles   只发给这些角色（{@code RoleConstants} 枚举名）；
     *                {@code null} 或空集 = 不限角色（与 {@link #publish} 等价）
     */
    public void publishTo(Long orderId, String type, Object data, Set<String> roles) {
        Map<String, WebSocketSession> group = groups.get(orderId);
        if (group == null || group.isEmpty()) {
            return;
        }
        String payload = buildPayload(orderId, type, data);
        if (payload == null) {
            return;
        }
        boolean filtered = roles != null && !roles.isEmpty();
        int sent = 0;
        int skipped = 0;
        for (WebSocketSession session : group.values()) {
            if (!session.isOpen()) {
                continue;
            }
            if (filtered) {
                // ⚠️ 先判 null 再 contains：{@code Set.of(...).contains(null)} 会抛 NPE，
                // 而缺 role 属性的会话（历史版本握手写入的旧会话 / 单测夹具）不能把整轮广播搞崩
                String role = roleOf(session);
                if (role == null || !roles.contains(role)) {
                    skipped++;
                    continue;
                }
            }
            try {
                synchronized (session) {
                    session.sendMessage(new TextMessage(payload));
                }
                sent++;
            } catch (Exception e) {
                log.warn("进度推送失败，已跳过该会话 | orderId={} | sessionId={} | {}",
                        orderId, session.getId(), e.getMessage());
            }
        }
        log.debug("进度事件已推送 | orderId={} | type={} | 成功会话数={} | 按角色跳过={}",
                orderId, type, sent, skipped);
    }

    /** 读会话角色；缺属性时返回 {@code null}（不被任何白名单命中，即不发） */
    private static String roleOf(WebSocketSession session) {
        Object value = session.getAttributes().get(WsAttributes.ROLE);
        return value instanceof String role ? role : null;
    }

    /** 单个会话回一条（PONG 用），不与分组广播混在一起 */
    public void sendTo(WebSocketSession session, String type, Object data) {
        String payload = buildPayload(null, type, data);
        if (payload == null || !session.isOpen()) {
            return;
        }
        try {
            synchronized (session) {
                session.sendMessage(new TextMessage(payload));
            }
        } catch (Exception e) {
            log.debug("单会话推送失败 | sessionId={} | {}", session.getId(), e.getMessage());
        }
    }

    /** 当前某个订单的订阅者数量（测试与运维观察用） */
    public int subscriberCount(Long orderId) {
        Map<String, WebSocketSession> group = groups.get(orderId);
        return group == null ? 0 : group.size();
    }

    /**
     * 组装事件报文：{@code {type, orderId, data}}，并在 {@code data} 里补上 {@code pushTime}。
     *
     * <p>{@code pushTime} 由服务端盖章而不是前端取本地时间 ——
     * 老人机、家属手机的时钟经常不准，用客户端时间排序会出现「后发生的事排在前面」。</p>
     */
    private String buildPayload(Long orderId, String type, Object data) {
        try {
            Map<String, Object> body = new HashMap<>(4);
            body.put("type", type);
            if (orderId != null) {
                body.put("orderId", orderId);
            }
            Map<String, Object> payload = new HashMap<>();
            if (data instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    payload.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            } else if (data != null) {
                payload.put("value", data);
            }
            payload.put("pushTime", LocalDateTime.now().format(PUSH_TIME_FORMAT));
            body.put("data", payload);
            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            // 不打内容，避免把订单备注等业务数据带进日志
            log.warn("进度事件序列化失败 | orderId={} | type={}", orderId, type);
            return null;
        }
    }
}

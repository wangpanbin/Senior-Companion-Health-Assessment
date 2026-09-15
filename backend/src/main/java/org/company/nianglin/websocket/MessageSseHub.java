package org.company.nianglin.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 站内信 SSE 推送中心（按用户分组）。
 *
 * <p>对应 {@code PLAN_BACKEND.md} §3：<b>M8 走 SSE（单向），M5 走 WebSocket（双向），两条通道不混用。</b>
 * 端点 {@code GET /sse/message}，鉴权直接走 {@code Authorization: Bearer} 头 ——
 * {@code EventSource} 是浏览器原生 API，断线重连由它自己负责，
 * 我们不需要像 M5 那样手写指数退避。</p>
 *
 * <h3>为什么这里用 {@code SseEmitter} 而 M5 用裸 WebSocket</h3>
 *
 * <p>两条通道的<b>消息流向不同</b>：M5 需要客户端上行（打卡回执、心跳），
 * 所以必须有双向能力；M8 只有「服务端 → 用户」一个方向，
 * 用 SSE 就少掉一整套帧协议与心跳约定，浏览器还会自动重连。</p>
 *
 * <h3>连接的生命周期必须自己管</h3>
 *
 * <p>SSE 是一个挂起的 HTTP 响应，用户关页面、切网络、Nginx 超时都会让它断开。
 * 三种回调（{@code onCompletion} / {@code onTimeout} / {@code onError}）都要摘除引用，
 * 否则 map 里会越积越多已经死掉的 emitter —— 每个都还占着内存和连接，
 * 而且推给它们时抛的异常会拖慢正常推送。</p>
 *
 * <p><b>回调不是唯一出路</b>：连接断掉时容器不一定立刻回调，所以
 * {@link #push} 在写失败时会<b>就地</b>摘除那条通道（见该方法注释）。
 * 两道保险缺一不可：回调负责「知道断了」，写失败负责「马上不再写它」。
 * 但<b>摘除 ≠ 收尾</b> —— 对已经写不通的响应调 {@code complete()}
 * 只会让容器再走一轮错误分发，把两条假 ERROR 写进日志。</p>
 *
 * @author 银龄伴诊团队
 * @since M8
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MessageSseHub {

    /** 连接超时：30 分钟。到点由前端 EventSource 自动重连，比服务端无限挂着更健康 */
    private static final long TIMEOUT_MILLIS = 30 * 60 * 1000L;

    /** 通道建立确认事件 */
    public static final String EVENT_CONNECTED = "CONNECTED";

    /** 新消息事件 */
    public static final String EVENT_NEW_MESSAGE = "NEW_MESSAGE";

    private static final DateTimeFormatter PUSH_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ConcurrentHashMap<Long, Set<SseEmitter>> groups = new ConcurrentHashMap<>();

    private final ObjectMapper objectMapper;

    /** 为用户建立一条推送通道 */
    public SseEmitter subscribe(Long userId) {
        return subscribe(userId, new SseEmitter(TIMEOUT_MILLIS));
    }

    /**
     * 建立通道的实际实现。
     *
     * <p>把 emitter 作为参数留出来，是为了让单测能塞进一个「一写就失败」的假通道 ——
     * 「失败的通道必须摘除、且绝不 {@code complete()}」这条性质用真实连接很难稳定复现，
     * 却正是那堆日志噪声的根因。同包可见，生产路径只走上面那个重载。</p>
     */
    SseEmitter subscribe(Long userId, SseEmitter emitter) {
        groups.computeIfAbsent(userId, k -> ConcurrentHashMap.newKeySet()).add(emitter);

        emitter.onCompletion(() -> remove(userId, emitter));
        emitter.onTimeout(() -> {
            remove(userId, emitter);
            // 超时的响应本身还是可写的，complete() 在这里是正常收尾。
            // 但仍加一层保护：若这条连接在超时前就已断开，complete() 会抛
            // AsyncRequestNotUsableException，再次触发上面说的错误分发。
            try {
                emitter.complete();
            } catch (Exception e) {
                log.debug("超时通道收尾失败（连接已断）| userId={}", userId);
            }
        });
        emitter.onError(e -> remove(userId, emitter));

        log.info("站内信推送通道已建立 | userId={} | 当前通道数={}", userId, connectionCount(userId));
        if (!sendTo(emitter, EVENT_CONNECTED, Map.of("userId", userId))) {
            // 连确认事件都发不出去，说明这条通道从建立起就已经死了。
            // 不摘除的话它会一直留在分组里，之后每次推送都白写一次、抛一次异常。
            remove(userId, emitter);
        }
        return emitter;
    }

    /**
     * 向某个用户推送事件。
     *
     * <p>失败只记日志：消息已经落库，前端还有「每 60 秒轮询
     * {@code /unread-count}」这条兜底路径（文档 §6 明确允许），
     * 一次推送失败不该影响发消息的主业务事务。</p>
     *
     * <p><b>失败的通道必须就地摘除。</b>不能只等 {@code onError} 回调 ——
     * 浏览器关页面 / 切网络时，服务端只有在<b>下一次写</b>时才会发现连接已断，
     * 在那之前这个死 emitter 会留在分组里，之后每次推送都要白白写一次、
     * 抛一次异常。摘除即可，<b>不要再对它调用 {@code complete()}</b>：
     * 那会在日志里留下两条与故障无关的 ERROR，理由见循环内注释。</p>
     */
    public void push(Long userId, String event, Object data) {
        Set<SseEmitter> emitters = groups.get(userId);
        if (emitters == null || emitters.isEmpty()) {
            return;
        }
        for (SseEmitter emitter : emitters) {
            if (sendTo(emitter, event, data)) {
                continue;
            }
            // 只摘引用，绝不调用 complete()。
            //
            // 写失败说明这条响应已经不可写，容器早把它标成 error 状态。
            // 此时 complete() 必然在 flush 阶段抛 AsyncRequestNotUsableException
            // （实测：Spring 6.1，StandardServletAsyncWebRequest$LifecycleHttpServletResponse
            // 抛「Response not usable after response errors」）。
            // 异常本身可以 try/catch 吞掉，但**错误状态吞不掉**：
            // 容器会对这次 /sse/message 再走一轮错误分发，日志里于是多出两条 ERROR ——
            // ① GlobalExceptionHandler 把「客户端关了页面」记成「系统异常」；
            // ② 错误分发时 SecurityContext 已不在，而这个端点要鉴权，
            //    AuthorizationFilter 抛 AccessDenied，ExceptionTranslationFilter
            //    又因为响应已提交而无法处理，再补一条 ERROR。
            // 两条都是假故障，会让真正的故障淹在噪声里。
            //
            // 连接已经断了，async 请求由容器自己回收；我们要做的只是让它不再被写。
            remove(userId, emitter);
        }
    }

    /** 当前该用户的活跃通道数（测试与运维观察用） */
    public int connectionCount(Long userId) {
        Set<SseEmitter> emitters = groups.get(userId);
        return emitters == null ? 0 : emitters.size();
    }

    /** @return 是否推送成功；失败表示这条通道已经死了，调用方应立刻摘除它 */
    private boolean sendTo(SseEmitter emitter, String event, Object data) {
        try {
            Map<String, Object> payload = new HashMap<>(4);
            if (data instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    payload.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            } else if (data != null) {
                payload.put("value", data);
            }
            payload.put("pushTime", LocalDateTime.now().format(PUSH_TIME_FORMAT));
            emitter.send(SseEmitter.event().name(event).data(objectMapper.writeValueAsString(payload)));
            return true;
        } catch (IOException | IllegalStateException e) {
            // 连接已断（用户关页面）会走到这里，属于正常情况，降级为 debug
            log.debug("站内信推送失败，通道可能已断开 | event={} | {}", event, e.getMessage());
            return false;
        } catch (Exception e) {
            log.warn("站内信推送异常 | event={} | {}", event, e.getMessage());
            return false;
        }
    }

    private void remove(Long userId, SseEmitter emitter) {
        Set<SseEmitter> emitters = groups.get(userId);
        if (emitters == null) {
            return;
        }
        emitters.remove(emitter);
        if (emitters.isEmpty()) {
            groups.remove(userId, emitters);
        }
    }
}

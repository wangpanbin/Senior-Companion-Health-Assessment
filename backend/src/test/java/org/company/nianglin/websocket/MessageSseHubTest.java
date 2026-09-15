package org.company.nianglin.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 站内信推送中心单测（M8）。
 *
 * <p>这里盯的是「<b>一条写不通的通道，怎么处理才是对的</b>」。
 * 这件事看起来只是清理，但处理错了会在日志里留下很响的假故障 ——
 * 实测 {@code GET /sse/message} 的客户端断开一次，原本会打出三条 ERROR 堆栈：
 * 先是推送时对已损坏的响应调 {@code complete()} 抛
 * {@code AsyncRequestNotUsableException}，再是容器错误分发撞上鉴权抛
 * {@code AccessDeniedException}，最后 Spring 与 Tomcat 各记一笔。</p>
 *
 * <p>正确做法只有两步：<b>摘除引用</b>（让后续推送不再碰它），
 * <b>不调用 complete()</b>（响应已经写不通，收尾必然失败且会触发错误分发）。
 * 这两条都在下面钉死，免得以后有人「顺手补一个 complete()」把噪声加回来。</p>
 *
 * <p>注意建通道时会先发一个 {@code CONNECTED} 事件，因此<b>每次成功的
 * {@code subscribe} 本身就消耗一次 {@code send}</b>；下面断言调用次数时都把这个算在内，
 * 否则很容易把「CONNECTED + 一次推送」误判成串号。</p>
 *
 * @author 银龄伴诊团队
 * @since M8
 */
@DisplayName("站内信推送：通道摘除 / 不做无效收尾 / 分组隔离")
class MessageSseHubTest {

    private final MessageSseHub hub = new MessageSseHub(new ObjectMapper());

    @Test
    @DisplayName("连接正常时建立通道，推送后仍在分组里")
    void aliveChannelKeepsDelivering() throws Exception {
        SseEmitter emitter = mock(SseEmitter.class);
        hub.subscribe(1L, emitter);
        assertEquals(1, hub.connectionCount(1L));

        hub.push(1L, MessageSseHub.EVENT_NEW_MESSAGE, Map.of("messageId", 1));

        assertEquals(1, hub.connectionCount(1L), "写得通就不该摘除");
        verify(emitter, never()).complete();
    }

    @Test
    @DisplayName("写失败的通道立刻摘除，且绝不调用 complete()")
    void deadChannelIsRemovedButNeverCompleted() throws Exception {
        SseEmitter emitter = mock(SseEmitter.class);
        hub.subscribe(1L, emitter);
        assertEquals(1, hub.connectionCount(1L));

        // 模拟「客户端关页面」：下一次写直接抛 IOException
        willThrow(new IOException("你的主机中的软件中止了一个已建立的连接"))
                .given(emitter).send(any(SseEmitter.SseEventBuilder.class));

        hub.push(1L, MessageSseHub.EVENT_NEW_MESSAGE, Map.of("messageId", 2));

        assertEquals(0, hub.connectionCount(1L), "失败的通道必须就地摘除");
        verify(emitter, never()).complete();
    }

    @Test
    @DisplayName("连确认事件都发不出去的死通道，建立时就被摘掉（不留在分组里）")
    void deadOnArrivalChannelIsRemovedAtSubscribe() throws Exception {
        SseEmitter emitter = mock(SseEmitter.class);
        willThrow(new IOException("broken pipe"))
                .given(emitter).send(any(SseEmitter.SseEventBuilder.class));

        hub.subscribe(1L, emitter);

        assertEquals(0, hub.connectionCount(1L));
        verify(emitter, never()).complete();
    }

    @Test
    @DisplayName("同一用户多条通道：坏的摘掉，好的继续收")
    void onlyDeadChannelIsRemoved() throws Exception {
        SseEmitter alive = mock(SseEmitter.class);
        SseEmitter dead = mock(SseEmitter.class);
        // 第一次（建通道时的 CONNECTED）成功，之后的推送才失败 ——
        // 这才是「先连上、之后客户端才断」的真实顺序。
        // 若一上来就失败，它会在 subscribe 阶段被判为「死通道」直接摘除，
        // 那样测的就不是 push 的清理逻辑了。
        doNothing().doThrow(new IOException("gone"))
                .when(dead).send(any(SseEmitter.SseEventBuilder.class));

        hub.subscribe(1L, alive);
        hub.subscribe(1L, dead);
        assertEquals(2, hub.connectionCount(1L));

        hub.push(1L, MessageSseHub.EVENT_NEW_MESSAGE, Map.of("messageId", 3));

        assertEquals(1, hub.connectionCount(1L), "只摘坏的那条");
        verify(dead, never()).complete();
        verify(alive, times(2)).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    @DisplayName("推送只到该用户，不串到别人")
    void pushDoesNotLeakAcrossUsers() throws Exception {
        SseEmitter mine = mock(SseEmitter.class);
        SseEmitter others = mock(SseEmitter.class);
        hub.subscribe(1L, mine);
        hub.subscribe(2L, others);

        hub.push(1L, MessageSseHub.EVENT_NEW_MESSAGE, Map.of("messageId", 5));

        // 各收各的：user 1 = CONNECTED + NEW_MESSAGE 共 2 次，user 2 只有 CONNECTED 1 次
        verify(mine, times(2)).send(any(SseEmitter.SseEventBuilder.class));
        verify(others, times(1)).send(any(SseEmitter.SseEventBuilder.class));
        assertEquals(1, hub.connectionCount(2L));
    }

    @Test
    @DisplayName("没有订阅者时推送静默返回，不抛异常")
    void pushWithoutSubscribersIsNoop() {
        hub.push(99L, MessageSseHub.EVENT_NEW_MESSAGE, Map.of());
        assertEquals(0, hub.connectionCount(99L));
    }
}

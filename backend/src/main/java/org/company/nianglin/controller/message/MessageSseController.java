package org.company.nianglin.controller.message;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.company.nianglin.service.MessageService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 站内信实时推送（SSE）。
 *
 * <p>端点：{@code GET /sse/message}，鉴权走 {@code Authorization: Bearer} 头
 * （{@code PLAN_BACKEND.md} §3）。</p>
 *
 * <h3>关于路径不在 {@code /api} 前缀下</h3>
 *
 * <p>项目约定「所有后端接口统一 {@code /api} 前缀」，这里是个刻意的例外：
 * SSE 是一个<b>长连接</b>，不是一个请求-响应接口。放在 {@code /api} 下会让
 * 前端的统一 Axios 封装（超时、重试、错误提示）误以为它是一个普通接口 ——
 * 而 Axios 的默认 30 秒超时会在连上之后准时把它掐断。
 * 用独立的 {@code /sse} 前缀，前端就必须显式用 {@code EventSource} 连接，
 * 这个「必须用对方式」的约束是有价值的。</p>
 *
 * <h3>为什么既做 SSE 又保留轮询</h3>
 *
 * <p>文档 §6 的实现要点写明「一期可用轮询兜底」。这里保留双通道：
 * SSE 负责「3 秒内看到红点变化」这条验收项，
 * 而 {@code /api/message/unread-count} 每 60 秒的轮询负责在
 * Nginx 未配 {@code proxy_buffering off}、企业代理掐长连接等环境问题下
 * 仍然可用。<b>两条通道读的是同一份数据库状态</b>，因此不会出现
 * 「SSE 说 8 条、轮询说 7 条」这种自相矛盾的情况。</p>
 *
 * @author 银龄伴诊团队
 * @since M8
 */
@Slf4j
@RestController
@RequestMapping("/sse")
@RequiredArgsConstructor
@Tag(name = "07-站内信-实时推送", description = "SSE 未读数实时推送通道")
public class MessageSseController {

    private final MessageService messageService;

    @Operation(summary = "订阅未读数实时推送",
            description = "返回 text/event-stream 长连接。事件名 NEW_MESSAGE，"
                    + "负载含 messageId / messageType / title / content / unreadCount / pushTime。"
                    + "连接超时 30 分钟，由浏览器 EventSource 自动重连；"
                    + "重连后建议先调一次 /api/message/unread-count 对齐数字")
    @GetMapping(value = "/message", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribe() {
        return messageService.subscribe();
    }
}

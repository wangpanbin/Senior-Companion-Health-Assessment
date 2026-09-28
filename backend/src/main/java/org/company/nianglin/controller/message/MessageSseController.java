package org.company.nianglin.controller.message;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.company.nianglin.common.ResultCode;
import org.company.nianglin.exception.BusinessException;
import org.company.nianglin.security.JwtTokenProvider;
import org.company.nianglin.security.LoginUser;
import org.company.nianglin.security.SecurityUtils;
import org.company.nianglin.security.TokenPayload;
import org.company.nianglin.security.TokenStore;
import org.company.nianglin.service.MessageService;
import org.company.nianglin.websocket.MessageSseHub;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 站内信实时推送（SSE）。
 *
 * <p>端点：{@code GET /sse/message}，鉴权走 {@code Authorization: Bearer} 头
 * 或 {@code ?token=} query 参数二选一（{@code PLAN_BACKEND.md} §3）。
 * 收口迭代 E4 起支持 query 令牌 —— 浏览器原生 {@code EventSource} 不能自定义
 * 请求头，令牌走 query 与 {@code /ws/progress} 是同一套做法；
 * 校验口径与 {@code JwtHandshakeInterceptor} 一致：验签 → access 类型 → 黑名单 → 密码版本。</p>
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
 * <h3>双通道口径</h3>
 *
 * <p>文档 §6 的实现要点写明「一期可用轮询兜底」。轮询通道
 * {@code /api/message/unread-count} 在 Nginx 未配 {@code proxy_buffering off}、
 * 企业代理掐长连接等环境问题下仍然可用；两条通道<b>读的是同一份数据库状态</b>，
 * 因此不会出现「SSE 说 8 条、轮询说 7 条」这种自相矛盾的情况。</p>
 *
 * @author 银龄伴诊团队
 * @since M8（query 令牌支持：收口迭代 E4）
 */
@Slf4j
@RestController
@RequestMapping("/sse")
@RequiredArgsConstructor
@Tag(name = "07-站内信-实时推送", description = "SSE 未读数实时推送通道")
public class MessageSseController {

    private final MessageService messageService;
    private final JwtTokenProvider tokenProvider;
    private final TokenStore tokenStore;
    private final MessageSseHub sseHub;

    @Operation(summary = "订阅未读数实时推送",
            description = "返回 text/event-stream 长连接。事件名 NEW_MESSAGE，"
                    + "负载含 messageId / messageType / title / content / unreadCount。"
                    + "鉴权：Authorization 头或 ?token= 二选一；连接超时 30 分钟，"
                    + "由浏览器 EventSource 自动重连；重连后建议先调一次 /api/message/unread-count 对齐数字")
    @GetMapping(value = "/message", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter message(@RequestParam(required = false) String token) {
        Long userId = resolveUserId(token);
        if (userId == null) {
            // 无令牌 / 令牌无效：立即正常关闭的 emitter。刻意不用 completeWithError ——
            // 错误分发会被 GlobalExceptionHandler 记成「系统异常」，是已知日志噪音源
            //（见 MessageSseHub 内注释）。前端 EventSource 会走 onerror 兜底拉取。
            log.warn("SSE 订阅被拒：缺少或无效 token");
            SseEmitter rejected = new SseEmitter(0L);
            rejected.complete();
            return rejected;
        }
        if (SecurityUtils.currentUserOrNull() != null) {
            // Authorization 头路径：走 service（保持既有校验与缓存行为）
            return messageService.subscribe();
        }
        // query 令牌路径：SecurityContext 里没有登录态，直接按 userId 建通道
        return sseHub.subscribe(userId);
    }

    /** 优先 SecurityContext（带 Authorization 头的调用方），否则校验 query 令牌 */
    private Long resolveUserId(String token) {
        LoginUser current = SecurityUtils.currentUserOrNull();
        if (current != null) {
            return current.userId();
        }
        if (token == null || token.isBlank()) {
            return null;
        }
        // 校验口径与 JwtHandshakeInterceptor 一致：验签 + access 类型 + 黑名单 + 密码版本
        try {
            TokenPayload payload = tokenProvider.parse(token);
            if (!payload.isAccess()) {
                throw new BusinessException(ResultCode.TOKEN_INVALID);
            }
            if (tokenStore.isBlacklisted(payload.jti())) {
                throw new BusinessException(ResultCode.TOKEN_INVALID);
            }
            if (payload.passwordVersion() != tokenStore.currentPasswordVersion(payload.userId())) {
                throw new BusinessException(ResultCode.TOKEN_INVALID);
            }
            return payload.userId();
        } catch (BusinessException e) {
            // 日志不打印 token 本身
            log.info("SSE query 令牌校验未通过 | code={} | message={}", e.getCode(), e.getMessage());
            return null;
        } catch (Exception e) {
            log.info("SSE query 令牌解析失败 | message={}", e.getMessage());
            return null;
        }
    }
}

package org.company.nianglin.controller.chat;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.company.nianglin.common.PageResult;
import org.company.nianglin.common.Result;
import org.company.nianglin.dto.ChatConversationQuery;
import org.company.nianglin.dto.ChatReadDTO;
import org.company.nianglin.dto.ChatSendDTO;
import org.company.nianglin.service.ChatService;
import org.company.nianglin.vo.ChatConversationVO;
import org.company.nianglin.vo.ChatHistoryVO;
import org.company.nianglin.vo.ChatMessageVO;
import org.company.nianglin.vo.ChatReadResultVO;
import org.company.nianglin.vo.ChatUnreadTotalVO;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 订单聊天接口（M13，ADR-0011）。
 *
 * <p>对应 {@code docs/api/11-order-chat.md} §1 ~ §5。实时接收端点
 * {@code /ws/chat} 见 {@code ChatWebSocketHandler}；管理端检索在
 * {@link AdminChatController}。</p>
 *
 * <h3>为什么写接口不加 {@code @PreAuthorize}（除会话列表/未读数外）</h3>
 *
 * <p>发送 / 已读的权限是「本单双方」，角色注解表达不了「哪一单」——
 * FAMILY 发别人的单、ADMIN 发任何单、ELDER 发自己的单，都要在
 * Service 的校验链里按订单逐单判定（{@code 3004} / {@code 7003} / {@code 7008}）。
 * ELDER 的写请求由 {@code ElderReadOnlyInterceptor} 默认拒绝（403），
 * 本控制器不标 {@code AllowElderWrite}，即「聊天写入不属于老人可代操作的例外」。</p>
 *
 * <p>会话列表与未读总数是纯角色接口（仅 FAMILY / COMPANION），用注解表达。</p>
 *
 * <h3>合规提醒</h3>
 *
 * <p>聊天内容与订单小结共用同一份合规词表（3007 口径）：命中「诊断 / 处方 /
 * 用药建议」类表述一律 7004，聊天室顶部仍须展示免责声明 ——
 * 前端文案，见 {@code docs/api/11-order-chat.md} 背景一节。</p>
 *
 * @author 银龄伴诊团队
 * @since M13
 */
@Slf4j
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
@Tag(name = "11-订单聊天", description = "家属↔陪诊员按订单实时聊天（文字 / 图片 / mp4 视频，ADR-0011）")
public class ChatController {

    private final ChatService chatService;

    @Operation(summary = "发送消息",
            description = "仅本单下单家属与接单陪诊员（会话双方）。TEXT 正文必填 ≤1000 字；"
                    + "IMAGE / VIDEO 须先调 POST /api/file/upload（bizType=CHAT）拿 fileId，"
                    + "且附件必须为本人上传（7006）、类型与消息类型匹配（7007）。"
                    + "订单未接单返回 7003；ELDER / ADMIN 等会话双方以外角色返回 7008"
                    + "（ELDER 通常已被只读拦截器先行 403）。命中合规词表返回 7004")
    @PostMapping("/{orderId}/messages")
    public Result<ChatMessageVO> send(
            @Parameter(description = "订单 ID", example = "1001") @PathVariable("orderId") Long orderId,
            @Valid @RequestBody ChatSendDTO dto) {
        return Result.success(chatService.send(orderId, dto));
    }

    @Operation(summary = "拉取历史消息（游标倒序）",
            description = "本单双方 + ELDER（自己订单）+ ADMIN 仲裁可读。records 按 messageId 降序"
                    + "（最新在前），前端反转为正序展示；cursor 传上一页的 nextCursor，"
                    + "不传或传 0 从最新开始。只读接口，不改变已读游标")
    @GetMapping("/{orderId}/messages")
    public Result<ChatHistoryVO> history(
            @Parameter(description = "订单 ID", example = "1001") @PathVariable("orderId") Long orderId,
            @Parameter(description = "游标：上一页返回的 nextCursor，0 表示从最新开始")
            @RequestParam(name = "cursor", required = false, defaultValue = "0") Long cursor,
            @Parameter(description = "每页条数，默认 20，上限 100")
            @RequestParam(name = "size", required = false, defaultValue = "20") Integer size) {
        return Result.success(chatService.history(orderId, cursor, size));
    }

    @Operation(summary = "我的会话列表",
            description = "仅 FAMILY / COMPANION：我作为下单家属或接单陪诊员的已接单订单，"
                    + "按最后一条消息倒序、无消息的按订单号垫底。对方姓名脱敏，不返回手机号")
    @PreAuthorize("hasAnyRole('FAMILY','COMPANION')")
    @GetMapping("/conversations")
    public Result<PageResult<ChatConversationVO>> conversations(
            @Valid ChatConversationQuery query) {
        return Result.success(chatService.conversations(query));
    }

    @Operation(summary = "已读上报",
            description = "仅本单双方。幂等：游标只前进不后退，重复上报不报错；"
                    + "lastMessageId 超过会话最大 id 时按最大 id 收敛。返回上报后的剩余未读数")
    @PostMapping("/{orderId}/read")
    public Result<ChatReadResultVO> markRead(
            @Parameter(description = "订单 ID", example = "1001") @PathVariable("orderId") Long orderId,
            @Valid @RequestBody ChatReadDTO dto) {
        return Result.success(chatService.markRead(orderId, dto));
    }

    @Operation(summary = "未读总数",
            description = "仅 FAMILY / COMPANION。跨所有我会话的未读求和，"
                    + "供「消息」页入口卡与角标兜底轮询（与站内信 60s 轮询同节奏）")
    @PreAuthorize("hasAnyRole('FAMILY','COMPANION')")
    @GetMapping("/unread-total")
    public Result<ChatUnreadTotalVO> unreadTotal() {
        return Result.success(chatService.unreadTotal());
    }
}

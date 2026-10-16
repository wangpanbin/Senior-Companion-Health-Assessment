package org.company.nianglin.controller.chat;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.company.nianglin.common.PageResult;
import org.company.nianglin.common.Result;
import org.company.nianglin.dto.AdminChatOrderQuery;
import org.company.nianglin.service.ChatService;
import org.company.nianglin.vo.AdminChatOrderVO;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端聊天查看接口（M13，ADR-0011，只读）。
 *
 * <p>对应 {@code docs/api/11-order-chat.md} §6：仲裁查看器的左栏订单检索。
 * 打开某单后的历史拉取与 WS 订阅复用 {@code /api/chat/{orderId}/messages}
 * 与 {@code /ws/chat}（{@code requireInvolved} 对 ADMIN 直通）；
 * 管理端<b>不发消息</b> —— 写操作在 Service 层落 7008，仲裁只读不变量。</p>
 *
 * <p>只读查询，不落 {@code admin_oper_log}（M9 口径：写操作才落）。</p>
 *
 * @author 银龄伴诊团队
 * @since M13
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/chat")
@RequiredArgsConstructor
@Tag(name = "11-订单聊天", description = "管理端仲裁查看器（只读）")
public class AdminChatController {

    private final ChatService chatService;

    @Operation(summary = "有聊天记录的订单检索",
            description = "仅 ADMIN。只列出产生过聊天消息的订单（含已取消订单 —— 取消后的交涉"
                    + "恰是仲裁场景），按最后一条消息时间倒序；keyword 模糊匹配订单号 / 医院。"
                    + "双方姓名脱敏，接口不返回任何消息内容 —— 内容在查看器里按单拉取")
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/orders")
    public Result<PageResult<AdminChatOrderVO>> orders(@Valid AdminChatOrderQuery query) {
        return Result.success(chatService.adminChatOrders(query));
    }
}

package org.company.nianglin.controller.message;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.company.nianglin.common.PageResult;
import org.company.nianglin.common.Result;
import org.company.nianglin.dto.MessageQuery;
import org.company.nianglin.dto.MessageReadAllDTO;
import org.company.nianglin.service.MessageService;
import org.company.nianglin.vo.MessageReadResultVO;
import org.company.nianglin.vo.MessageVO;
import org.company.nianglin.vo.UnreadCountVO;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 站内信接口。
 *
 * <p>对应 {@code docs/api/07-message.md} §1 ~ §5。实时推送端点见
 * {@link MessageSseController}（{@code /sse/message}）。</p>
 *
 * <h3>为什么全部接口都没有 {@code @PreAuthorize}</h3>
 *
 * <p>站内信的可见范围是「收件人 = 当前登录用户」，与角色无关 ——
 * 老人、家属、陪诊员、管理员都只看自己的消息。
 * 角色注解在这里既不必要也不充分：它挡不住「家属 A 看家属 B 的消息」，
 * 那件事只能靠 {@code receiver_id} 条件 + Service 层归属校验。</p>
 *
 * <h3>写操作对 ELDER 的处理</h3>
 *
 * <p>「标记已读 / 删除」是老人<b>应该能做</b>的动作（看自己的消息、清掉不看的），
 * 不属于「必须由家属代做」的敏感写操作。但 {@code ElderReadOnlyInterceptor}
 * 默认拒绝 ELDER 的一切写方法，因此这三个接口需要显式标注
 * {@link org.company.nianglin.security.AllowElderWrite} 放行 ——
 * 这正是该注解存在的意义：默认安全，例外必须一个一个说明理由。</p>
 *
 * @author 银龄伴诊团队
 * @since M8
 */
@Slf4j
@RestController
@RequestMapping("/api/message")
@RequiredArgsConstructor
@Tag(name = "07-站内信", description = "站内信列表、未读数、已读与删除")
public class MessageController {

    private final MessageService messageService;

    /* ==================== 查询 ==================== */

    @Operation(summary = "消息列表",
            description = "只返回当前登录用户自己的消息，按发送时间倒序。"
                    + "支持按类型（逗号分隔多值）、已读状态、时间区间筛选")
    @GetMapping
    public Result<PageResult<MessageVO>> list(@Valid MessageQuery query) {
        return Result.success(messageService.list(query));
    }

    @Operation(summary = "未读数",
            description = "返回未读总数与按类型分组的明细。前端顶栏红点只依赖本接口 + "
                    + "SSE 推送；通道未就绪时可每 60 秒轮询本接口兜底")
    @GetMapping("/unread-count")
    public Result<UnreadCountVO> unreadCount() {
        return Result.success(messageService.unreadCount());
    }

    /* ==================== 已读与删除 ==================== */

    @Operation(summary = "标记单条已读",
            description = "幂等：已读的消息再次标记不报错、不重复扣减未读数。"
                    + "不是自己的消息返回 7002，消息不存在返回 7001")
    @org.company.nianglin.security.AllowElderWrite("老人查看并标记自己的站内信为已读，属于只读之外的正常个人操作")
    @PutMapping("/{id}/read")
    public Result<MessageReadResultVO> markRead(
            @Parameter(description = "消息 ID", example = "50001") @PathVariable("id") Long id) {
        return Result.success(messageService.markRead(id));
    }

    @Operation(summary = "全部已读",
            description = "不传 type 则全部标记为已读；传 type 只标记该类（可逗号分隔多值）")
    @org.company.nianglin.security.AllowElderWrite("老人批量标记自己的站内信为已读，同上")
    @PutMapping("/read-all")
    public Result<MessageReadResultVO> markAllRead(@RequestBody(required = false) MessageReadAllDTO dto) {
        return Result.success("已全部标记为已读",
                messageService.markAllRead(dto == null ? null : dto.getType()));
    }

    @Operation(summary = "删除消息",
            description = "逻辑删除，只影响接收人自己的视图，服务端仍保留发送记录。"
                    + "不是自己的消息返回 7002")
    @org.company.nianglin.security.AllowElderWrite("老人删除自己的站内信，属于个人视图管理，不影响任何业务数据")
    @DeleteMapping("/{id}")
    public Result<Void> delete(
            @Parameter(description = "消息 ID", example = "50001") @PathVariable("id") Long id) {
        messageService.delete(id);
        return Result.success("已删除", null);
    }
}

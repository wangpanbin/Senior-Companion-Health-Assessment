package org.company.nianglin.dto;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.company.nianglin.common.PageQuery;
import org.company.nianglin.entity.InternalMessage;

/**
 * 站内信列表查询入参（{@code GET /api/message}）。
 *
 * <p>对应 {@code docs/api/07-message.md} §1。</p>
 *
 * <h3>为什么没有 {@code receiverId}</h3>
 *
 * <p>收件人恒等于当前登录用户，由令牌决定。这里放开一个 {@code receiverId}
 * 就等于把「能看谁的消息」交给前端 —— 站内信里含订单号、老人姓名、
 * 病名（用药提醒文案）等，越权的代价比普通列表高得多。</p>
 *
 * @author 银龄伴诊团队
 * @since M8
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "站内信列表查询入参")
public class MessageQuery extends PageQuery<InternalMessage> {

    @Schema(description = "消息类型，可多值逗号分隔", example = "ORDER_ACCEPTED,MEDICATION_REMIND")
    @Size(max = 200, message = "消息类型参数过长")
    private String type;

    @Schema(description = "已读状态：true 已读 / false 未读，不传为全部", example = "false")
    private Boolean isRead;

    @Schema(description = "起始日期（含），格式 yyyy-MM-dd", example = "2026-09-01")
    private String startDate;

    @Schema(description = "结束日期（含），格式 yyyy-MM-dd", example = "2026-09-30")
    private String endDate;

    /** MyBatis-Plus 分页对象 */
    public Page<InternalMessage> toMpPage() {
        return toPage(new Page<>());
    }
}

package org.company.nianglin.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;
import org.company.nianglin.constant.MessageType;
import org.company.nianglin.entity.InternalMessage;

import java.time.LocalDateTime;

/**
 * 站内信（{@code docs/api/07-message.md} §一 MessageVO）。
 *
 * <h3>{@code isRead} 为什么是布尔值而不是 0/1</h3>
 *
 * <p>与其他 {@code TINYINT} 列一致，实体里是 {@code Integer}；
 * 但对外的语义是「读没读过」，是布尔。转换放在 VO 里做，
 * 让前端写 {@code v-if="!msg.isRead"} 而不是 {@code v-if="msg.isRead === 0"}</p>。
 *
 * <h3>标题与正文已经脱敏</h3>
 *
 * <p>正文由 {@code MessageTemplateUtil} 在后端填充，模板里的姓名类占位符
 * 由调用方（各业务的 send 点）<b>先脱敏再传入</b>。
 * 本 VO 不再做二次处理 —— 二次脱敏会把「李*」变成「李*」，看似无害，
 * 但一旦有人误传全名进来，VO 就成了唯一的补救点，那时候就晚了。
 * 所以约定是：<b>进库前脱敏，出库照原样返回</b>，并有单测锁定。</p>
 *
 * @author 银龄伴诊团队
 * @since M8
 */
@Data
@Accessors(chain = true)
@Schema(description = "站内信")
public class MessageVO {

    @Schema(description = "消息 ID", example = "50001")
    private Long id;

    @Schema(description = "消息类型枚举名", example = "ORDER_ACCEPTED")
    private String type;

    @Schema(description = "消息类型中文", example = "订单已接单")
    private String typeLabel;

    @Schema(description = "标题", example = "您的订单已被接单")
    private String title;

    @Schema(description = "正文（后端模板填充，姓名已脱敏）")
    private String content;

    @Schema(description = "关联业务类型", example = "ORDER")
    private String bizType;

    @Schema(description = "关联业务 ID", example = "1001")
    private Long bizId;

    @Schema(description = "前端跳转路径", example = "/family?orderId=1001")
    private String linkUrl;

    @Schema(description = "是否已读", example = "false")
    private Boolean isRead;

    @Schema(description = "阅读时间", example = "2026-09-15 17:10:00")
    private LocalDateTime readTime;

    @Schema(description = "发送时间", example = "2026-09-15 17:02:11")
    private LocalDateTime createTime;

    public static MessageVO of(InternalMessage row) {
        if (row == null) {
            return null;
        }
        return new MessageVO()
                .setId(row.getId())
                .setType(row.getType())
                .setTypeLabel(MessageType.labelOf(row.getType()))
                .setTitle(row.getTitle())
                .setContent(row.getContent())
                .setBizType(row.getBizType())
                .setBizId(row.getBizId())
                .setLinkUrl(row.getLinkUrl())
                .setIsRead(row.getIsRead() != null && row.getIsRead() == 1)
                .setReadTime(row.getReadTime())
                .setCreateTime(row.getCreateTime());
    }
}

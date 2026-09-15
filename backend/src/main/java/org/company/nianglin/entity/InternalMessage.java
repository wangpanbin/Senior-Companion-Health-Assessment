package org.company.nianglin.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.company.nianglin.common.BaseEntity;
import java.time.LocalDateTime;

/**
 * InternalMessage —— 对应表 {@code internal_message}。
 *
 * <p>站内信表</p>
 *
 * <p>主键与审计字段（id / createTime / updateTime / deleted）继承自 {@link BaseEntity}。</p>
 *
 * <p>⚠️ 本类为持久化实体，禁止直接作为接口返回值；对外统一转 VO，
 * 避免密码、身份证号、完整手机号等敏感字段外泄。</p>
 *
 * @since M1
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("internal_message")
public class InternalMessage extends BaseEntity {

    /**
     * 接收人用户 ID
     */
    @Schema(description = "接收人用户 ID")
    private Long receiverId;

    /**
     * 发送人用户 ID，NULL 表示系统发送
     */
    @Schema(description = "发送人用户 ID，NULL 表示系统发送")
    private Long senderId;

    /**
     * 消息类型：ORDER_CREATED / ORDER_ACCEPTED / ORDER_PROGRESS / ORDER_COMPLETED / ORDER_CANCELLED / AUDIT_RESULT / MEDICATION_REMIND / COMPLAINT_HANDLED / SYSTEM_NOTICE
     */
    @Schema(description = "消息类型：ORDER_CREATED / ORDER_ACCEPTED / ORDER_PROGRESS / ORDER_COMPLETED / ORDER_CANCELLED / AUDIT_RESULT / MEDICATION_REMIND / COMPLAINT_HANDLED / SYSTEM_NOTICE")
    private String type;

    /**
     * 标题，最长 50 字
     */
    @Schema(description = "标题，最长 50 字")
    private String title;

    /**
     * 正文，最长 500 字，**由后端模板填充并脱敏，前端禁止拼文案**
     */
    @Schema(description = "正文，最长 500 字，**由后端模板填充并脱敏，前端禁止拼文案**")
    private String content;

    /**
     * 关联业务类型：ORDER-订单 / AUDIT-资质 / MEDICATION-用药 / COMPLAINT-投诉 / SYSTEM-系统
     */
    @Schema(description = "关联业务类型：ORDER-订单 / AUDIT-资质 / MEDICATION-用药 / COMPLAINT-投诉 / SYSTEM-系统")
    private String bizType;

    /**
     * 关联业务 ID，用于点击跳转
     */
    @Schema(description = "关联业务 ID，用于点击跳转")
    private Long bizId;

    /**
     * 前端跳转路径，如 /family?orderId=1001
     */
    @Schema(description = "前端跳转路径，如 /family?orderId=1001")
    private String linkUrl;

    /**
     * 是否已读：0-未读 1-已读
     */
    @TableField("is_read")
    @Schema(description = "是否已读：0-未读 1-已读")
    private Integer isRead;

    /**
     * 阅读时间
     */
    @Schema(description = "阅读时间")
    private LocalDateTime readTime;

    /**
     * 接收人侧删除标记：0-正常 1-已删除（仅影响接收人视图）
     */
    @Schema(description = "接收人侧删除标记：0-正常 1-已删除（仅影响接收人视图）")
    private Integer receiverDeleted;

}

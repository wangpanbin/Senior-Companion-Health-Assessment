package org.company.nianglin.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.company.nianglin.common.BaseEntity;
import java.time.LocalDateTime;

/**
 * SmsSendLog —— 对应表 {@code sms_send_log}。
 *
 * <p>短信发送回执表（幂等 + 指数退避 + 死信，V4 P1-3）</p>
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
@TableName("sms_send_log")
public class SmsSendLog extends BaseEntity {

    /**
     * 业务类型，如 MEDICATION_MISSED
     */
    @Schema(description = "业务类型，如 MEDICATION_MISSED")
    private String bizType;

    /**
     * 业务主键（漏服提醒为 medication_task.id）
     */
    @Schema(description = "业务主键（漏服提醒为 medication_task.id）")
    private Long bizId;

    /**
     * 接收人用户 ID
     */
    @Schema(description = "接收人用户 ID")
    private Long userId;

    /**
     * 脱敏手机号（只存 138****8888，禁止存完整号码）
     */
    @Schema(description = "脱敏手机号（只存 138****8888，禁止存完整号码）")
    private String phoneMasked;

    /**
     * 发送场景：verification / missed-dose
     */
    @Schema(description = "发送场景：verification / missed-dose")
    private String scene;

    /**
     * 模板 Code 快照（便于排查「用了哪个模板」）
     */
    @Schema(description = "模板 Code 快照（便于排查「用了哪个模板」）")
    private String templateCode;

    /**
     * 状态：PENDING-待发送 / SENT-已发送 / FAILED-永久失败不重试 / DEAD-死信（重试耗尽或合规拦截）
     */
    @Schema(description = "状态：PENDING-待发送 / SENT-已发送 / FAILED-永久失败不重试 / DEAD-死信（重试耗尽或合规拦截）")
    private String status;

    /**
     * 已尝试发送次数
     */
    @Schema(description = "已尝试发送次数")
    private Integer attemptCount;

    /**
     * 最后一次失败原因（截断 500 字，禁止写入完整手机号）
     */
    @Schema(description = "最后一次失败原因（截断 500 字，禁止写入完整手机号）")
    private String lastError;

    /**
     * 幂等键：提醒任务 + 渠道 + 日期，唯一索引兜底
     */
    @Schema(description = "幂等键：提醒任务 + 渠道 + 日期，唯一索引兜底")
    private String dedupKey;

    /**
     * 计划发送时间（入队时间）
     */
    @Schema(description = "计划发送时间（入队时间）")
    private LocalDateTime scheduledAt;

    /**
     * 实际发送成功时间
     */
    @Schema(description = "实际发送成功时间")
    private LocalDateTime sentAt;

    /**
     * 下次重试时间；静默时段内顺延到次日 08:00
     */
    @Schema(description = "下次重试时间；静默时段内顺延到次日 08:00")
    private LocalDateTime nextRetryAt;

}

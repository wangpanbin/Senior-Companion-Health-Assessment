package org.company.nianglin.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.company.nianglin.common.BaseEntity;
import java.time.LocalDateTime;

/**
 * Complaint —— 对应表 {@code complaint}。
 *
 * <p>投诉表</p>
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
@TableName("complaint")
public class Complaint extends BaseEntity {

    /**
     * 关联订单 ID
     */
    @Schema(description = "关联订单 ID")
    private Long orderId;

    /**
     * 订单号快照
     */
    @Schema(description = "订单号快照")
    private String orderNo;

    /**
     * 投诉人用户 ID
     */
    @Schema(description = "投诉人用户 ID")
    private Long complainantId;

    /**
     * 投诉人角色
     */
    @Schema(description = "投诉人角色")
    private String complainantRole;

    /**
     * 被投诉人用户 ID
     */
    @Schema(description = "被投诉人用户 ID")
    private Long targetUserId;

    /**
     * 被投诉人角色
     */
    @Schema(description = "被投诉人角色")
    private String targetRole;

    /**
     * 投诉类型：LATE-迟到/未按时到达 / ATTITUDE-服务态度差 / INCOMPLETE-服务未完成 / FEE_DISPUTE-费用纠纷 / PRIVACY-隐私泄露 / OTHER-其他
     */
    @Schema(description = "投诉类型：LATE-迟到/未按时到达 / ATTITUDE-服务态度差 / INCOMPLETE-服务未完成 / FEE_DISPUTE-费用纠纷 / PRIVACY-隐私泄露 / OTHER-其他")
    private String type;

    /**
     * 投诉内容，10-1000 字
     */
    @Schema(description = "投诉内容，10-1000 字")
    private String content;

    /**
     * 证据材料 URL 数组，最多 6 张
     */
    @Schema(description = "证据材料 URL 数组，最多 6 张")
    private String evidence;

    /**
     * 处理状态：PENDING-待处理 / PROCESSING-处理中 / RESOLVED-已结案 / REJECTED-已驳回（**只可正向流转**）
     */
    @Schema(description = "处理状态：PENDING-待处理 / PROCESSING-处理中 / RESOLVED-已结案 / REJECTED-已驳回（**只可正向流转**）")
    private String status;

    /**
     * 处理管理员用户 ID
     */
    @Schema(description = "处理管理员用户 ID")
    private Long handleAdminId;

    /**
     * 处理结果说明（10-500 字）
     */
    @Schema(description = "处理结果说明（10-500 字）")
    private String handleResult;

    /**
     * 是否对被投诉人计违规：0-否 1-是
     */
    @Schema(description = "是否对被投诉人计违规：0-否 1-是")
    private Integer penaltyToTarget;

    /**
     * 处理时间
     */
    @Schema(description = "处理时间")
    private LocalDateTime handleTime;

}

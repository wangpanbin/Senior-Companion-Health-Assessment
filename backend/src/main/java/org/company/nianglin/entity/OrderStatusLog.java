package org.company.nianglin.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.company.nianglin.common.BaseEntity;
import java.time.LocalDateTime;

/**
 * OrderStatusLog —— 对应表 {@code order_status_log}。
 *
 * <p>订单状态流转日志表（只增不改）</p>
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
@TableName("order_status_log")
public class OrderStatusLog extends BaseEntity {

    /**
     * 订单 ID
     */
    @Schema(description = "订单 ID")
    private Long orderId;

    /**
     * 变更前状态，首次下单为 NULL
     */
    @Schema(description = "变更前状态，首次下单为 NULL")
    private String fromStatus;

    /**
     * 变更后状态
     */
    @Schema(description = "变更后状态")
    private String toStatus;

    /**
     * 操作人用户 ID，系统自动流转为 NULL
     */
    @Schema(description = "操作人用户 ID，系统自动流转为 NULL")
    private Long operatorId;

    /**
     * 操作人姓名快照
     */
    @Schema(description = "操作人姓名快照")
    private String operatorName;

    /**
     * 操作人角色：ELDER / FAMILY / COMPANION / ADMIN / SYSTEM
     */
    @Schema(description = "操作人角色：ELDER / FAMILY / COMPANION / ADMIN / SYSTEM")
    private String operatorRole;

    /**
     * 备注（如「管理员强制变更」）
     */
    @Schema(description = "备注（如「管理员强制变更」）")
    private String remark;

    /**
     * 是否管理员强制变更：0-否 1-是
     */
    @TableField("is_force")
    @Schema(description = "是否管理员强制变更：0-否 1-是")
    private Integer isForce;

    /**
     * 发生时间
     */
    @Schema(description = "发生时间")
    private LocalDateTime operateTime;

}

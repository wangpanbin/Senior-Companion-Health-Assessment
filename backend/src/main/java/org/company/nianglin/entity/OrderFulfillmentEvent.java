package org.company.nianglin.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.company.nianglin.common.BaseEntity;
import java.time.LocalDateTime;

/**
 * OrderFulfillmentEvent —— 对应表 {@code order_fulfillment_event}。
 *
 * <p>订单履约事件流水表（只增不改，与 order_status_log 语义不同）</p>
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
@TableName("order_fulfillment_event")
public class OrderFulfillmentEvent extends BaseEntity {

    /**
     * 关联 companion_order.id
     */
    @Schema(description = "关联 companion_order.id")
    private Long orderId;

    /**
     * 事件类型：ORDER_CREATED / MATCHING_STARTED / FULFILLMENT_WARNING / USER_URGED / FULFILLMENT_URGENT / MANUAL_REQUIRED / ADMIN_COORDINATED / ORDER_REASSIGNED
     */
    @Schema(description = "事件类型：ORDER_CREATED / MATCHING_STARTED / FULFILLMENT_WARNING / USER_URGED / FULFILLMENT_URGENT / MANUAL_REQUIRED / ADMIN_COORDINATED / ORDER_REASSIGNED")
    private String eventType;

    /**
     * 变更前风险等级；首次事件为 NULL
     */
    @Schema(description = "变更前风险等级；首次事件为 NULL")
    private String fromRisk;

    /**
     * 变更后风险等级；非风险类事件可与 from_risk 相同
     */
    @Schema(description = "变更后风险等级；非风险类事件可与 from_risk 相同")
    private String toRisk;

    /**
     * 触发来源：USER-用户操作 / ADMIN-管理员 / SYSTEM-系统自动
     */
    @Schema(description = "触发来源：USER-用户操作 / ADMIN-管理员 / SYSTEM-系统自动")
    private String triggerSource;

    /**
     * 操作人用户 ID；系统自动触发为 NULL
     */
    @Schema(description = "操作人用户 ID；系统自动触发为 NULL")
    private Long operatorId;

    /**
     * 操作人角色快照：FAMILY / COMPANION / ADMIN / SYSTEM
     */
    @Schema(description = "操作人角色快照：FAMILY / COMPANION / ADMIN / SYSTEM")
    private String operatorRole;

    /**
     * 备注（如管理员协调说明；写入前已过合规词表）
     */
    @Schema(description = "备注（如管理员协调说明；写入前已过合规词表）")
    private String remark;

    /**
     * 发生时间
     */
    @Schema(description = "发生时间")
    private LocalDateTime operateTime;

}

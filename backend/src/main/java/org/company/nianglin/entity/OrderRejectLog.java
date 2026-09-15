package org.company.nianglin.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.company.nianglin.common.BaseEntity;
import java.time.LocalDateTime;

/**
 * OrderRejectLog —— 对应表 {@code order_reject_log}。
 *
 * <p>陪诊员拒单记录表</p>
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
@TableName("order_reject_log")
public class OrderRejectLog extends BaseEntity {

    /**
     * 订单 ID
     */
    @Schema(description = "订单 ID")
    private Long orderId;

    /**
     * 拒单陪诊员用户 ID
     */
    @Schema(description = "拒单陪诊员用户 ID")
    private Long companionId;

    /**
     * 拒单原因
     */
    @Schema(description = "拒单原因")
    private String reason;

    /**
     * 拒单时间
     */
    @Schema(description = "拒单时间")
    private LocalDateTime rejectTime;

}

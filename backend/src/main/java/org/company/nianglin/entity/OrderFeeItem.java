package org.company.nianglin.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.company.nianglin.common.BaseEntity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * OrderFeeItem —— 对应表 {@code order_fee_item}。
 *
 * <p>订单费用明细（ADR-0009）：代垫（ADVANCE，家属还给陪诊员）与服务费（SERVICE）
 * 分账，是线下结算的最小对账单元；{@code companion_order.actual_fee} 由明细
 * SUM 派生，读取时不一致以明细为准。</p>
 *
 * <p>⚠️ 本类为持久化实体，禁止直接作为接口返回值；对外统一转 VO。
 * 费用明细只记录支出项目与金额，**不含任何医疗诊断信息**（plan.md §一红线）。</p>
 *
 * @since M4（收敛迭代 T2.5）
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("order_fee_item")
public class OrderFeeItem extends BaseEntity {

    /**
     * 关联 companion_order.id
     */
    @Schema(description = "关联 companion_order.id")
    private Long orderId;

    /**
     * 费用类型：ADVANCE-代垫 / SERVICE-服务费
     */
    @Schema(description = "费用类型：ADVANCE-代垫（家属还给陪诊员）/ SERVICE-服务费")
    private String itemType;

    /**
     * 项目名，如「心内科挂号费」「陪诊服务费」
     */
    @Schema(description = "项目名，如「心内科挂号费」「陪诊服务费」")
    private String itemName;

    /**
     * 金额（元），两位小数
     */
    @Schema(description = "金额（元），两位小数")
    private BigDecimal amount;

    /**
     * 费用发生时间
     */
    @Schema(description = "费用发生时间")
    private LocalDateTime occurredAt;
}

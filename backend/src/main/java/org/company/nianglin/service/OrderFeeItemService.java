package org.company.nianglin.service;

import org.company.nianglin.dto.FeeItemCreateDTO;
import org.company.nianglin.vo.OrderFeeItemSummaryVO;
import org.company.nianglin.vo.OrderFeeItemVO;

import java.math.BigDecimal;

/**
 * 订单费用明细（ADR-0009）：明细是真源，actual_fee 是汇总缓存。
 *
 * @author 银龄伴诊团队
 * @since M4（收敛迭代 T2.5）
 */
public interface OrderFeeItemService {

    /** 陪诊员录入一条明细；完成态订单同步重算 actual_fee */
    OrderFeeItemVO create(Long orderId, FeeItemCreateDTO dto);

    /** 相关方查询明细与分组合计 */
    OrderFeeItemSummaryVO listByOrder(Long orderId);

    /** 订单完成时随状态变更同事务生成服务费明细并重算 actual_fee（ADR-0009 写入路径） */
    void applyOnComplete(Long orderId, BigDecimal declaredFee);
}

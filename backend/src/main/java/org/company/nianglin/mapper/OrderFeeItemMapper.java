package org.company.nianglin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.company.nianglin.entity.OrderFeeItem;

/**
 * OrderFeeItem 数据访问接口。
 *
 * <p>订单费用明细表（ADR-0009）；通用 CRUD 由 {@link BaseMapper} 提供。</p>
 *
 * @since M4（收敛迭代 T2.5）
 */
@Mapper
public interface OrderFeeItemMapper extends BaseMapper<OrderFeeItem> {
}

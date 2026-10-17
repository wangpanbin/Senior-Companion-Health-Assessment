package org.company.nianglin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.company.nianglin.entity.OrderFulfillmentEvent;

/**
 * OrderFulfillmentEvent 数据访问接口。
 *
 * <p>订单履约事件流水表（只增不改，与 order_status_log 语义不同）</p>
 *
 * <p>通用 CRUD 由 {@link BaseMapper} 提供；本接口只声明复杂查询，
 * 复杂 SQL 写在 {@code resources/mapper/OrderFulfillmentEventMapper.xml} 中。</p>
 *
 * @since M1
 */
@Mapper
public interface OrderFulfillmentEventMapper extends BaseMapper<OrderFulfillmentEvent> {
}

package org.company.nianglin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.company.nianglin.entity.OrderFulfillmentEvent;

/**
 * OrderFulfillmentEvent 数据访问接口。
 *
 * <p>订单履约事件流水表（只增不改，与 order_status_log 语义不同）</p>
 *
 * <p>通用 CRUD 由 {@link BaseMapper} 提供，够用且没有额外声明。
 * 本表没有复杂查询，因此<b>不需要</b>配套 XML ——
 * {@code resources/mapper/} 下不存在本 Mapper 的映射文件，
 * 与 {@code AGENTS.md} §1「resources/mapper/（如有）」一致。</p>
 *
 * @since V3.1
 */
@Mapper
public interface OrderFulfillmentEventMapper extends BaseMapper<OrderFulfillmentEvent> {
}

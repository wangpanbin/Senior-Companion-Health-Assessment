package org.company.nianglin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.company.nianglin.entity.CompanionOrder;

/**
 * CompanionOrder 数据访问接口。
 *
 * <p>陪诊订单主表</p>
 *
 * <p>通用 CRUD 由 {@link BaseMapper} 提供；本接口只声明复杂查询，
 * 复杂 SQL 写在 {@code resources/mapper/CompanionOrderMapper.xml} 中。</p>
 *
 * @since M1
 */
@Mapper
public interface CompanionOrderMapper extends BaseMapper<CompanionOrder> {

    /**
     * 按 id 取订单并加行锁（{@code SELECT ... FOR UPDATE}）。
     *
     * <p><b>只用于「读明细 → 汇总 → 写回 actual_fee」这种派生字段重算</b>：
     * {@code actual_fee} 是明细的聚合结果，两笔明细并发录入时，
     * 先算的一方可能拿着较旧的合计后提交，把较新的合计覆盖掉。
     * 锁住订单行相当于把同一张单的录入段串行化，后到的一方一定读到前一方已提交的明细。</p>
     *
     * <p>⚠️ 手写 SQL 不走 MyBatis-Plus 的逻辑删除插件，{@code deleted = 0} 显式写上。
     * 调用方必须在事务内使用。</p>
     *
     * @param id 订单 ID
     * @return 订单实体；不存在或已逻辑删除时返回 {@code null}
     */
    @Select("SELECT * FROM companion_order WHERE id = #{id} AND deleted = 0 FOR UPDATE")
    CompanionOrder selectByIdForUpdate(@Param("id") Long id);
}

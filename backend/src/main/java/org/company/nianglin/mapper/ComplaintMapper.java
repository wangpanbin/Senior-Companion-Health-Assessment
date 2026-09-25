package org.company.nianglin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.company.nianglin.entity.Complaint;

/**
 * Complaint 数据访问接口。
 *
 * <p>投诉表</p>
 *
 * <p>通用 CRUD 由 {@link BaseMapper} 提供；本接口只声明复杂查询，
 * 复杂 SQL 写在 {@code resources/mapper/ComplaintMapper.xml} 中。</p>
 *
 * @since M1
 */
@Mapper
public interface ComplaintMapper extends BaseMapper<Complaint> {

    /**
     * 同一评价的未结案申诉数（E4 评价公信力闭环）。
     *
     * <p>通过「评价 ID → 评价所属订单 ID → 该订单的 REVIEW_APPEAL 投诉」
     * 三段反查统计。评价与申诉用 {@code order_id} 间接关联，
     * 不在 {@code complaint} 表加 {@code review_id} 列 —— 「E4 零迁移」承诺。</p>
     *
     * <p>子查询命中 {@code order_review.id} 主键 + {@code order_id} 索引，O(1) 一次扫描；
     * 外层走 {@code (type, status, order_id, deleted)} 的窄索引（投诉表既有），不会全表。</p>
     */
    @Select("""
            SELECT COUNT(*)
            FROM complaint
            WHERE type = 'REVIEW_APPEAL'
              AND status IN ('PENDING', 'PROCESSING')
              AND order_id = (SELECT order_id FROM order_review WHERE id = #{reviewId})
              AND deleted = 0
            """)
    long countOpenAppealByReviewId(@Param("reviewId") Long reviewId);
}

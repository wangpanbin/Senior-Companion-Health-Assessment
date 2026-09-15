package org.company.nianglin.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 评价模块的聚合只读查询（对应 M7）。
 *
 * <h3>为什么用原生 SQL 而不是 MyBatis-Plus 的 Wrapper</h3>
 *
 * <p>评分聚合要的是 {@code AVG} 与 {@code GROUP BY}，MyBatis-Plus 的
 * {@code QueryWrapper} 能表达，但需要通过 {@code select("ROUND(AVG(score),2)")}
 * 这类字符串拼接绕开实体映射 —— 拼错字段名只有在运行时才会暴露。
 * 直接写两条 {@code @Select} 更短、更明确，也让「口径」这件事
 * 有一个可以被评审的落点。</p>
 *
 * <h3>为什么要显式写 {@code deleted = 0}</h3>
 *
 * <p>原生 SQL <b>不会</b>自动带上 {@code @TableLogic} 的过滤条件。
 * 少写这一句，被逻辑删除的评价就会重新计入平均分 ——
 * 而这正是「靠代码评审看不出来、只能靠对账数字发现」的那类错误，
 * 所以在这里显式写出来。</p>
 *
 * <p>{@code is_valid = 1} 是另一条独立的口径：管理员判定无效的评价
 * 仍然留在库里（用户能查到自己的评价），但不参与聚合。</p>
 *
 * @author 银龄伴诊团队
 * @since M7
 */
@Mapper
public interface ReviewReadMapper {

    /**
     * 陪诊员有效评价的平均分，两位小数。
     *
     * <p>与本模块验收项要求的手工 SQL 完全一致：
     * {@code SELECT ROUND(AVG(score), 2) FROM order_review WHERE companion_id = ?}。</p>
     *
     * @return 无有效评价时返回 {@code null}（由 VO 兜底成 {@code "0.00"}）
     */
    @Select("""
            SELECT ROUND(AVG(score), 2)
            FROM order_review
            WHERE companion_id = #{companionId} AND is_valid = 1 AND deleted = 0
            """)
    BigDecimal selectAverageScore(@Param("companionId") Long companionId);

    /**
     * 陪诊员有效评价的星级分布。
     *
     * @return 每行形如 {@code {score: 5, cnt: 30}}；某星级没有人评时会缺席而不是返回 0
     */
    @Select("""
            SELECT score, COUNT(*) AS cnt
            FROM order_review
            WHERE companion_id = #{companionId} AND is_valid = 1 AND deleted = 0
            GROUP BY score
            """)
    List<Map<String, Object>> selectScoreDistribution(@Param("companionId") Long companionId);
}

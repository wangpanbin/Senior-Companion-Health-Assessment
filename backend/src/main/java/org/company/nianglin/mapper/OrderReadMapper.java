package org.company.nianglin.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.company.nianglin.entity.ElderProfile;

import java.util.Collection;
import java.util.List;

/**
 * 订单模块的跨表只读查询。
 *
 * <h3>为什么不直接往 ElderProfileMapper 里加方法</h3>
 *
 * <p>{@code ElderProfileMapper} 是 M1 由 {@code gen_entity.py} 从
 * {@code V1__init_schema.sql} <b>单向生成</b>的产物。往里加自定义方法，
 * 下次任何人重跑生成器就会把它抹掉 —— 而编译器不会提醒，
 * 只会有一堆「找不到符号」在别人的分支上炸开。所以自定义查询一律放进独立接口。</p>
 *
 * <h3>为什么这里的查询要"绕过逻辑删除"</h3>
 *
 * <p>{@link ElderProfile} 上的 {@code @TableLogic} 会让所有 MyBatis-Plus 生成的
 * 查询自动带上 {@code AND deleted = 0}。这对列表是对的（删掉的老人不该出现在列表里），
 * 但对订单是错的：M3 的删除流程<b>刻意保留物理行</b>，理由写在
 * {@code docs/api/02-elder-family.md} §10 ——「历史订单、用药记录、评价都要 JOIN 它」。</p>
 *
 * <p>如果此处也跟着过滤，家属点开三个月前那笔已完成订单，会看到一张
 * <b>没有老人姓名的订单</b>，而这正是 M3 验收项「删除老人档案后历史订单仍可正常查看」
 * 要保证的事情。用原生 {@code @Select}（不经 MyBatis-Plus 的 SQL 注入环节）
 * 才能读到那一行。</p>
 *
 * <p>⚠️ 因此本接口只允许用于<b>「订单已经存在，回来补名字」</b>这种场景。
 * 任何「判断一个老人能不能被访问 / 能不能被操作」的校验，都必须走
 * {@code ElderService}，那里会正确地过滤掉已删除档案。</p>
 *
 * @author 银龄伴诊团队
 * @since M4
 */
@Mapper
public interface OrderReadMapper {

    /**
     * 按主键批量读老人基础信息（<b>不过滤逻辑删除</b>，仅取展示所需字段）。
     *
     * @param elderIds 老人档案 ID 集合，调用方保证非空
     * @return 命中的档案（可能少于入参个数），字段仅 id / userId / name / birthDate / gender
     */
    @Select("""
            <script>
            SELECT id, user_id, name, gender, birth_date
            FROM elder_profile
            WHERE id IN
            <foreach collection="elderIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    List<ElderProfile> selectEldersIgnoringLogicDelete(@Param("elderIds") Collection<Long> elderIds);
}

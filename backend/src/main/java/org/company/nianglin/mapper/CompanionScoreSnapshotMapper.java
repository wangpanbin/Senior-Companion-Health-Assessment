package org.company.nianglin.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 陪诊员评分快照的<b>原子</b>刷新（M7 · BUG_LIST L2）。
 *
 * <h3>为什么需要这个 mapper</h3>
 *
 * <p>旧实现是「先 {@code AVG} 查出来、再用 Wrapper 写回」两条语句：
 * 两次执行之间若并发提交了另一条评价，前一次算出的快照会覆盖后一次，
 * 表现为 {@code companion_profile.score} 与 {@code order_review} 实际聚合对不上 ——
 * 且这种丢失更新只在高并发评价下偶发，极难复现。</p>
 *
 * <p>本接口把「算」与「写」合成<b>一条</b> {@code UPDATE}：子查询在同一条语句内读取，
 * 与写入之间不存在可插入并发写的窗口。</p>
 *
 * <h3>口径必须与 {@link ReviewReadMapper} 完全一致</h3>
 *
 * <ul>
 *   <li>{@code is_valid = 1} —— 管理员裁定无效的评价不参与聚合（仍保留在库里可查）；</li>
 *   <li>{@code deleted = 0} —— 原生 SQL 不会自动带上 {@code @TableLogic} 过滤，
 *       少写这一句会让逻辑删除的评价重新计入平均分；</li>
 *   <li>目标表 {@code companion_profile} 的 {@code deleted = 0} 对齐旧实现
 *       （MyBatis-Plus Wrapper 更新会自动补 {@code @TableLogic} 条件，
 *       手写 SQL 必须显式补回来，否则口径漂移）。</li>
 * </ul>
 *
 * <p>目标表与子查询表不同（{@code companion_profile} vs {@code order_review}），
 * 不存在 MySQL「更新自身表须用派生表绕 1093」的限制。</p>
 *
 * @author 银龄伴诊团队
 * @since M7 · V4 收口（BUG_LIST L2）
 */
@Mapper
public interface CompanionScoreSnapshotMapper {

    /**
     * 按 {@code order_review} 的实时聚合重算并写入 {@code companion_profile} 快照。
     *
     * <p>平均分无有效评价时写 {@code 0}（与 {@code CompanionScoreVO} 的
     * {@code "0.00"} 兜底口径一致）。</p>
     *
     * <p>调用方必须处于评价写入的事务内（见 {@code ReviewServiceImpl#refreshCompanionScore}），
     * 快照与评价记录要么一起生效、要么一起回滚。</p>
     *
     * @param companionId 陪诊员用户 ID（= {@code order_review.companion_id} = {@code companion_profile.user_id}）
     * @return 实际更新的行数（陪诊员无资料页时为 0，与旧实现的 Wrapper 更新一致）
     */
    @Update("""
            UPDATE companion_profile
            SET score = COALESCE((
                    SELECT ROUND(AVG(score), 2)
                    FROM order_review
                    WHERE companion_id = #{companionId} AND is_valid = 1 AND deleted = 0
                ), 0),
                review_count = (
                    SELECT COUNT(*)
                    FROM order_review
                    WHERE companion_id = #{companionId} AND is_valid = 1 AND deleted = 0
                )
            WHERE user_id = #{companionId} AND deleted = 0
            """)
    int refreshByCompanion(@Param("companionId") Long companionId);
}

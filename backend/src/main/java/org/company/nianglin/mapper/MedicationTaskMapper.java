package org.company.nianglin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.company.nianglin.entity.MedicationTask;

import java.time.LocalDateTime;

/**
 * MedicationTask 数据访问接口。
 *
 * <p>每日服药任务表（定时任务生成，唯一索引保证幂等）</p>
 *
 * <p>通用 CRUD 由 {@link BaseMapper} 提供；本接口只声明复杂查询，
 * 复杂 SQL 写在 {@code resources/mapper/MedicationTaskMapper.xml} 中。</p>
 *
 * @since M1
 */
@Mapper
public interface MedicationTaskMapper extends BaseMapper<MedicationTask> {

    /**
     * 物理删除「未来未确认」的任务（收口 B · P1-5 方案 2）。
     *
     * <p><b>必须绕过 {@code @TableLogic}</b>：{@code uk_plan_time(plan_id, plan_time)} 不含
     * {@code deleted}，软删行仍占唯一键；重新启用时先查后插护栏看不到软删行，
     * 插入必然撞键并被静默吞掉。这些任务从未发生，硬删是正确语义。</p>
     *
     * <p>只删 {@code status = 'PENDING'} 且 {@code plan_time > cutoff}；
     * 已发生的 {@code TAKEN} / {@code MISSED} 与已过时点一律保留。</p>
     */
    @Delete("""
            DELETE FROM medication_task
            WHERE plan_id = #{planId}
              AND status = 'PENDING'
              AND plan_time > #{cutoff}
            """)
    int deleteFuturePendingPhysically(@Param("planId") Long planId,
                                      @Param("cutoff") LocalDateTime cutoff);
}

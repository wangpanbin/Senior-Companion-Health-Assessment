package org.company.nianglin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.company.nianglin.entity.MedicationTask;

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
}

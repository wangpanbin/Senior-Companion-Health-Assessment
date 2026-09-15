package org.company.nianglin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.company.nianglin.entity.CompanionProfile;

/**
 * CompanionProfile 数据访问接口。
 *
 * <p>陪诊员业务资料表</p>
 *
 * <p>通用 CRUD 由 {@link BaseMapper} 提供；本接口只声明复杂查询，
 * 复杂 SQL 写在 {@code resources/mapper/CompanionProfileMapper.xml} 中。</p>
 *
 * @since M1
 */
@Mapper
public interface CompanionProfileMapper extends BaseMapper<CompanionProfile> {
}

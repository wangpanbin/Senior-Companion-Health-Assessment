package org.company.nianglin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.company.nianglin.entity.SysFile;

/**
 * SysFile 数据访问接口。
 *
 * <p>附件统一登记表</p>
 *
 * <p>通用 CRUD 由 {@link BaseMapper} 提供；本接口只声明复杂查询，
 * 复杂 SQL 写在 {@code resources/mapper/SysFileMapper.xml} 中。</p>
 *
 * @since M1
 */
@Mapper
public interface SysFileMapper extends BaseMapper<SysFile> {
}

package org.company.nianglin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.company.nianglin.entity.VitalSignRecord;

/**
 * VitalSignRecord 数据访问接口（V4 · P2-2）。
 *
 * <p>通用 CRUD 由 {@link BaseMapper} 提供；列表 / 趋势 / 撤回均用
 * {@code LambdaQueryWrapper} 表达，无需自定义 SQL。</p>
 *
 * @author 银龄伴诊团队
 * @since V4 · P2-2
 */
@Mapper
public interface VitalSignRecordMapper extends BaseMapper<VitalSignRecord> {
}

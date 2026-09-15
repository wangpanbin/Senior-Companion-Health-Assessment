package org.company.nianglin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.company.nianglin.entity.CompanionProfile;

import java.util.List;

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

    /**
     * 按 user_id 加行锁查询（{@code SELECT ... FOR UPDATE}）。
     *
     * <p>{@code companion_profile.user_id} 存在唯一索引，行不存在时 InnoDB 会取
     * <b>间隙锁</b>，对同一 {@code user_id} 的并发 {@code INSERT} 同样阻塞 —— 这是
     * 「陪诊员资质申请并发提交」修复的核心机制。</p>
     */
    @Select("SELECT * FROM companion_profile WHERE user_id = #{userId} AND deleted = 0 FOR UPDATE")
    List<CompanionProfile> selectForUpdateByUserId(@Param("userId") Long userId);
}

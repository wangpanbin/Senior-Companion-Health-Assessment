package org.company.nianglin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.company.nianglin.entity.ElderProfile;

/**
 * ElderProfile 数据访问接口。
 *
 * <p>老人档案表</p>
 *
 * <p>通用 CRUD 由 {@link BaseMapper} 提供；本接口只声明复杂查询，
 * 复杂 SQL 写在 {@code resources/mapper/ElderProfileMapper.xml} 中。</p>
 *
 * @since M1
 */
@Mapper
public interface ElderProfileMapper extends BaseMapper<ElderProfile> {

    /**
     * 按 id 取档案并加行锁（{@code SELECT ... FOR UPDATE}）。
     *
     * <p><b>只用于「先查后写」需要串行化的场景</b> —— 目前是家属绑定老人：
     * 「一位老人只允许一位主要家属绑定」的判定与写入之间没有唯一键可依赖
     * （{@code uk_family_elder} 只约束「同一对家属 + 老人」），
     * 并发下两个家属会同时通过读时校验。锁住 elder 行相当于把这一段串行化。</p>
     *
     * <p>⚠️ 手写 SQL 不会经过 MyBatis-Plus 的逻辑删除插件，所以 {@code deleted = 0}
     * 必须显式写上，否则会把已删除的档案锁出来。调用方必须在事务内使用，
     * 否则锁随语句结束即释放，等于没加。</p>
     *
     * @param id 档案 ID
     * @return 档案实体；不存在或已逻辑删除时返回 {@code null}
     */
    @Select("SELECT * FROM elder_profile WHERE id = #{id} AND deleted = 0 FOR UPDATE")
    ElderProfile selectByIdForUpdate(@Param("id") Long id);
}

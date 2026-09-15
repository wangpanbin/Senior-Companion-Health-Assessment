package org.company.nianglin.service.support;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.company.nianglin.constant.RoleConstants;
import org.company.nianglin.entity.CompanionProfile;
import org.company.nianglin.entity.SysUser;
import org.company.nianglin.mapper.CompanionProfileMapper;
import org.company.nianglin.mapper.SysUserMapper;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 操作人 / 参与方显示名解析（跨模块复用）。
 *
 * <h3>为什么需要单独一个组件</h3>
 *
 * <p>「这个用户 ID 该显示成什么名字」在 M7（评价人、投诉双方）、
 * M8（消息发送人）、M9（操作日志）里都要问一遍，而答案并不简单：</p>
 *
 * <center>
 * <table border="1">
 *   <caption>取名优先级</caption>
 *   <tr><th>角色</th><th>取值顺序</th><th>为什么</th></tr>
 *   <tr><td>COMPANION</td><td>{@code companion_profile.real_name} → 兜底</td>
 *       <td>陪诊员的真实姓名经过资质审核，是<b>权威</b>值；
 *           {@code sys_user.nickname} 是他自己能随便改的</td></tr>
 *   <tr><td>其余</td><td>{@code sys_user.real_name} → {@code nickname} → {@code username}</td>
 *       <td>没有资质档案可依据，逐级降级到一定不会为空的那一个</td></tr>
 * </table>
 * </center>
 *
 * <p>把这段逻辑写三遍的后果不是「多写了几行」，而是三个模块对同一个人的显示名
 * 出现了三种结果 —— 管理员在投诉详情里看到「李*」，在操作日志里看到「小李」，
 * 对不上号时会怀疑是两个人。</p>
 *
 * <h3>批量接口是主路径</h3>
 *
 * <p>{@link #resolveAll} 是给列表用的：一页 10 条评价如果逐条查库，
 * 就是 10 次以上往返。批量接口一次性把 {@code sys_user} 与
 * {@code companion_profile} 各查一次，把 N+1 收敛成 2。</p>
 *
 * @author 银龄伴诊团队
 * @since M7
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserNameResolver {

    private final SysUserMapper sysUserMapper;
    private final CompanionProfileMapper companionProfileMapper;

    /** 单个解析；用户不存在返回 {@code null}（调用方决定兜底文案） */
    public String resolve(Long userId) {
        if (userId == null) {
            return null;
        }
        return resolveAll(List.of(userId)).get(userId);
    }

    /**
     * 批量解析显示名。
     *
     * @param userIds 用户 ID 集合，允许为空
     * @return 用户 ID → 显示名；查不到的 ID 不会出现在结果里
     */
    public Map<Long, String> resolveAll(Collection<Long> userIds) {
        Map<Long, String> result = new HashMap<>();
        if (userIds == null || userIds.isEmpty()) {
            return result;
        }

        List<SysUser> users = sysUserMapper.selectList(Wrappers.<SysUser>lambdaQuery()
                .in(SysUser::getId, userIds)
                .select(SysUser::getId, SysUser::getRole, SysUser::getRealName,
                        SysUser::getNickname, SysUser::getUsername));
        if (users.isEmpty()) {
            return result;
        }

        Set<Long> companionIds = new LinkedHashSet<>();
        for (SysUser user : users) {
            if (RoleConstants.COMPANION.equals(user.getRole())) {
                companionIds.add(user.getId());
            }
        }
        Map<Long, String> companionNames = companionRealNames(companionIds);

        for (SysUser user : users) {
            String companionName = companionNames.get(user.getId());
            result.put(user.getId(), companionName != null ? companionName : fallback(user));
        }
        return result;
    }

    /** 陪诊员资质档案里的真实姓名，key 为 {@code user_id}（不是 profile 主键） */
    private Map<Long, String> companionRealNames(Collection<Long> userIds) {
        Map<Long, String> names = new HashMap<>();
        if (userIds == null || userIds.isEmpty()) {
            return names;
        }
        for (CompanionProfile profile : companionProfileMapper.selectList(
                Wrappers.<CompanionProfile>lambdaQuery()
                        .in(CompanionProfile::getUserId, userIds)
                        .select(CompanionProfile::getUserId, CompanionProfile::getRealName))) {
            if (StringUtils.hasText(profile.getRealName())) {
                names.put(profile.getUserId(), profile.getRealName());
            }
        }
        return names;
    }

    private static String fallback(SysUser user) {
        if (StringUtils.hasText(user.getRealName())) {
            return user.getRealName();
        }
        if (StringUtils.hasText(user.getNickname())) {
            return user.getNickname();
        }
        return user.getUsername();
    }
}

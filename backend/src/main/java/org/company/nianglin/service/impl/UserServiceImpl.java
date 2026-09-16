package org.company.nianglin.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.company.nianglin.common.ResultCode;
import org.company.nianglin.constant.AccountStatus;
import org.company.nianglin.dto.ProfileUpdateDTO;
import org.company.nianglin.entity.SysUser;
import org.company.nianglin.exception.BusinessException;
import org.company.nianglin.mapper.SysUserMapper;
import org.company.nianglin.security.SecurityUtils;
import org.company.nianglin.service.ElderService;
import org.company.nianglin.service.UserService;
import org.company.nianglin.vo.UserInfoVO;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 当前用户资料服务实现。
 *
 * @author 银龄伴诊团队
 * @since M3
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final SysUserMapper sysUserMapper;
    private final ElderService elderService;

    @Override
    public UserInfoVO getProfile() {
        SysUser user = requireActiveCurrentUser();
        return UserInfoVO.of(user, elderService.elderIdOf(user));
    }

    @Override
    public void updateProfile(ProfileUpdateDTO dto) {
        Long userId = SecurityUtils.currentUserId();
        // 必须校验「账号未封禁」：封禁后 JWT 仍可能有效（ver 不变），被禁用账号不应继续改资料
        requireActiveCurrentUser();

        SysUser patch = new SysUser();
        patch.setId(userId);

        boolean nicknameChanged = dto.getNickname() != null && StringUtils.hasText(dto.getNickname());
        boolean avatarChanged = dto.getAvatar() != null && StringUtils.hasText(dto.getAvatar());

        if (nicknameChanged) {
            patch.setNickname(dto.getNickname().trim());
        }
        if (avatarChanged) {
            patch.setAvatar(dto.getAvatar().trim());
        }

        if (!nicknameChanged && !avatarChanged) {
            // 两个字段都没传：直接返回，不做一次无意义的 UPDATE
            return;
        }

        // 只更新 patch 上的非空字段（MyBatis-Plus updateById 默认忽略 null）
        sysUserMapper.updateById(patch);

        // 日志只打「改了哪些字段」与 userId；不打昵称内容，也不打完整用户对象
        log.info("用户资料已更新 | userId={} | nicknameChanged={} | avatarChanged={}",
                userId, nicknameChanged, avatarChanged);
    }

    /**
     * 取当前登录用户实体，并要求账号处于「正常」状态。
     *
     * <p>令牌有效但账号已被物理删除时返回 401 而不是 404 —— 前端拿到 401 会清空登录态
     * 跳登录页，这是正确的处置；拿到 404 只会弹个「资源不存在」然后卡在原地。</p>
     *
     * <p>封禁账号即使 JWT 仍有效（ver 未变），任何写操作都要拒绝 —— 与
     * {@code AuthServiceImpl.currentUser} 的禁用拦截保持一致，避免出现
     * 「改密后被封、但旧令牌还能改昵称」的越权裂缝。</p>
     */
    private SysUser requireActiveCurrentUser() {
        SysUser user = sysUserMapper.selectById(SecurityUtils.currentUserId());
        if (user == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        if (AccountStatus.DISABLED.equals(user.getStatus())) {
            throw new BusinessException(ResultCode.ACCOUNT_DISABLED);
        }
        return user;
    }
}

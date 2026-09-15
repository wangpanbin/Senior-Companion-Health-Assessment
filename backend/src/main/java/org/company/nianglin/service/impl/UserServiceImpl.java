package org.company.nianglin.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.company.nianglin.common.ResultCode;
import org.company.nianglin.dto.ProfileUpdateDTO;
import org.company.nianglin.entity.SysUser;
import org.company.nianglin.exception.BusinessException;
import org.company.nianglin.mapper.SysUserMapper;
import org.company.nianglin.security.SecurityUtils;
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

    @Override
    public UserInfoVO getProfile() {
        return UserInfoVO.of(requireCurrentUser());
    }

    @Override
    public void updateProfile(ProfileUpdateDTO dto) {
        Long userId = SecurityUtils.currentUserId();
        // 先确认账号还在：updateById 作用在不存在的 ID 上会静默影响 0 行，
        // 接口返回「保存成功」而实际什么都没改，这种假成功最难排查
        requireCurrentUser();

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
     * 取当前登录用户实体。
     *
     * <p>令牌有效但账号已被物理删除时返回 401 而不是 404 —— 前端拿到 401 会清空登录态
     * 跳登录页，这是正确的处置；拿到 404 只会弹个「资源不存在」然后卡在原地。</p>
     */
    private SysUser requireCurrentUser() {
        SysUser user = sysUserMapper.selectById(SecurityUtils.currentUserId());
        if (user == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        return user;
    }
}

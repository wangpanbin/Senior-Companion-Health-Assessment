package org.company.nianglin.service;

import org.company.nianglin.dto.ProfileUpdateDTO;
import org.company.nianglin.vo.UserInfoVO;

/**
 * 当前用户资料服务。
 *
 * <p>对应 {@code docs/api/02-elder-family.md} §1 / §2。</p>
 *
 * <p>注意本接口<b>没有任何方法接收 userId 参数</b> —— 全部从登录态取。
 * 这是刻意的：一旦接口签名里出现 userId，早晚会有人从请求参数里把它填进来。</p>
 *
 * @author 银龄伴诊团队
 * @since M3
 */
public interface UserService {

    /** 获取当前登录用户资料 */
    UserInfoVO getProfile();

    /**
     * 更新当前登录用户资料（只更新传入的非空字段）。
     *
     * <p>⚠️ 老人账号（ELDER）调用本方法会被 {@code ElderReadOnlyInterceptor}
     * 在进入 Controller 之前拦掉（403），连这里都到不了 —— 符合「老人账号只读」的产品规则。</p>
     */
    void updateProfile(ProfileUpdateDTO dto);
}

package org.company.nianglin.service;

import org.company.nianglin.common.ClientInfo;
import org.company.nianglin.dto.ChangePasswordDTO;
import org.company.nianglin.dto.LoginDTO;
import org.company.nianglin.dto.RegisterDTO;
import org.company.nianglin.vo.LoginVO;
import org.company.nianglin.vo.TokenVO;
import org.company.nianglin.vo.UserInfoVO;

/**
 * 认证与账号服务。
 *
 * <p>覆盖 {@code docs/api/01-auth-user.md} 除验证码之外的 6 个接口。</p>
 *
 * @author 银龄伴诊团队
 */
public interface AuthService {

    /**
     * 注册。
     *
     * <p>用户名 / 手机号唯一；密码 BCrypt 加密后入库；{@code ADMIN} 角色一律拒绝。</p>
     *
     * @return 新用户 ID
     */
    Long register(RegisterDTO dto);

    /**
     * 登录。
     *
     * <p>校验顺序：验证码 → 锁定状态 → 账号密码 → 封禁状态。
     * 账号不存在与密码错误返回<b>同一个</b>错误码，防止账号枚举。</p>
     *
     * @param client 客户端信息（写登录日志用）
     */
    LoginVO login(LoginDTO dto, ClientInfo client);

    /**
     * 用 refreshToken 换新的 accessToken。
     *
     * @param refreshToken 登录时下发的刷新令牌
     * @param client       客户端信息（写登录日志用）
     */
    TokenVO refresh(String refreshToken, ClientInfo client);

    /**
     * 登出。
     *
     * <p>把当前访问令牌加入 Redis 黑名单；传入的 refreshToken 一并拉黑。</p>
     *
     * @param refreshToken 可选，为空则只拉黑访问令牌
     */
    void logout(String refreshToken);

    /** 获取当前登录用户信息（用于刷新页面后恢复登录态） */
    UserInfoVO currentUser();

    /**
     * 修改密码。
     *
     * <p>成功后密码版本 +1，该用户<b>所有</b>已签发令牌立即失效，需重新登录。</p>
     */
    void changePassword(ChangePasswordDTO dto);
}

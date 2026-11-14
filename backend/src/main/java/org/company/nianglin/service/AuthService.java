package org.company.nianglin.service;

import org.company.nianglin.common.ClientInfo;
import org.company.nianglin.dto.ChangePasswordDTO;
import org.company.nianglin.dto.ForgotPasswordDTO;
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
     * 生成找回密码验证码并直接回传（V4 · P1-1，去短信化见 ADR-0021）。
     *
     * <p>不再经过任何短信通道：验证码写入 Redis 后原样返回给调用方。
     * 无论手机号是否注册，响应形态完全一致（同构自动成立）。</p>
     *
     * @param phone    11 位手机号
     * @param clientIp 请求方 IP，用于「同 IP 每小时 20 次」频控；为空时跳过该窗口
     * @return 6 位数字验证码（明文回传，仅受三层频控约束）
     */
    String sendSmsCode(String phone, String clientIp);

    /**
     * 凭手机号 + 短信验证码自助找回密码（V4 · P1-1）。
     *
     * <p>手机号不存在时与成功路径返回完全同构，且不真正改密 —— 防止账号枚举。</p>
     */
    void forgotPassword(ForgotPasswordDTO dto);

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

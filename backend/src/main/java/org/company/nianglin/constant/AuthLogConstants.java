package org.company.nianglin.constant;

/**
 * 登录日志常量（对应 {@code sys_login_log}）。
 *
 * <p>⚠️ 禁止把密码写入 {@code fail_reason}；该字段只记录粗粒度原因，
 * 目的是「同一原因下无法区分账号是否存在」，避免账号枚举。</p>
 *
 * @author 银龄伴诊团队
 */
public final class AuthLogConstants {

    private AuthLogConstants() {
    }

    /* ---------------- login_type ---------------- */

    /** 登录 */
    public static final String TYPE_LOGIN = "LOGIN";

    /** 登出 */
    public static final String TYPE_LOGOUT = "LOGOUT";

    /** 刷新令牌 */
    public static final String TYPE_REFRESH = "REFRESH";

    /* ---------------- status ---------------- */

    /** 成功 */
    public static final String RESULT_SUCCESS = "SUCCESS";

    /** 失败 */
    public static final String RESULT_FAIL = "FAIL";

    /* ---------------- fail_reason ---------------- */

    /** 账号不存在（与「密码错误」对外表现一致，防账号枚举） */
    public static final String REASON_ACCOUNT_NOT_FOUND = "账号不存在";

    /** 密码错误 */
    public static final String REASON_BAD_PASSWORD = "密码错误";

    /** 验证码错误或已过期 */
    public static final String REASON_BAD_CAPTCHA = "验证码错误";

    /** 账号已被封禁 */
    public static final String REASON_ACCOUNT_DISABLED = "账号封禁";

    /** 失败次数过多被锁定 */
    public static final String REASON_LOGIN_LOCKED = "登录锁定";

    /** 令牌失效（刷新时 refreshToken 不合法） */
    public static final String REASON_TOKEN_INVALID = "令牌失效";
}

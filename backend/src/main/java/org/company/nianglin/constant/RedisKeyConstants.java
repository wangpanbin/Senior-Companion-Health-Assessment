package org.company.nianglin.constant;

/**
 * Redis key 统一命名。
 *
 * <p>所有 key 一律通过本类的方法拼接，<b>禁止</b>在业务代码里手写字符串字面量，
 * 否则排查线上问题时无法一眼看出某个 key 属于哪个模块。</p>
 *
 * <p>命名规范：{@code 模块:用途:标识}，全部小写，冒号分隔。</p>
 *
 * @author 银龄伴诊团队
 */
public final class RedisKeyConstants {

    private RedisKeyConstants() {
    }

    /** 图形验证码：{@code captcha:{captchaKey}} → 验证码文本，TTL = nianglin.security.captcha-expire-seconds */
    public static final String CAPTCHA_PREFIX = "captcha:";

    /** 令牌黑名单：{@code token:blacklist:{jti}} → 1，TTL = 令牌剩余有效期 */
    public static final String TOKEN_BLACKLIST_PREFIX = "token:blacklist:";

    /** 密码版本：{@code pwd:version:{userId}} → 递增整数，与 JWT 载荷 ver 比对 */
    public static final String PASSWORD_VERSION_PREFIX = "pwd:version:";

    /** 登录失败计数：{@code login:fail:{account}} → 次数，TTL = nianglin.security.login-lock-minutes */
    public static final String LOGIN_FAIL_PREFIX = "login:fail:";

    public static String captcha(String captchaKey) {
        return CAPTCHA_PREFIX + captchaKey;
    }

    public static String tokenBlacklist(String jti) {
        return TOKEN_BLACKLIST_PREFIX + jti;
    }

    public static String passwordVersion(Long userId) {
        return PASSWORD_VERSION_PREFIX + userId;
    }

    public static String loginFail(String account) {
        return LOGIN_FAIL_PREFIX + account;
    }
}

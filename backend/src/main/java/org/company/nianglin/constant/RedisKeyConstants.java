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

    /** 订单号当日序列：{@code order:seq:{yyyyMMdd}} → 递增整数，TTL 2 天 */
    public static final String ORDER_SEQ_PREFIX = "order:seq:";

    /** 站内信未读数：{@code message:unread:{userId}} → 未读条数，短 TTL，变更即失效 */
    public static final String MESSAGE_UNREAD_PREFIX = "message:unread:";

    /** 陪诊员评分聚合：{@code companion:score:{companionId}} → JSON，评价提交后失效 */
    public static final String COMPANION_SCORE_PREFIX = "companion:score:";

    /** 定时任务分布式锁：{@code nianglin:lock:{biz}} → 持锁者 uuid，SET NX PX + Lua 释放 */
    public static final String LOCK_PREFIX = "nianglin:lock:";

    /** 封禁标记：{@code user:banned:{userId}} → 1，用于让已签发令牌立即失效（M9） */
    public static final String USER_BANNED_PREFIX = "user:banned:";

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

    /** 按日期分桶的订单号序列；每天从 1 重新开始，所以订单号里的日期与序号永远成对出现 */
    public static String orderSeq(String yyyyMMdd) {
        return ORDER_SEQ_PREFIX + yyyyMMdd;
    }

    /** 某用户的未读数缓存 */
    public static String messageUnread(Long userId) {
        return MESSAGE_UNREAD_PREFIX + userId;
    }

    /** 某陪诊员的评分聚合缓存 */
    public static String companionScore(Long companionId) {
        return COMPANION_SCORE_PREFIX + companionId;
    }

    /** 定时任务锁；{@code biz} 用业务名，如 {@code medication:daily} */
    public static String lock(String biz) {
        return LOCK_PREFIX + biz;
    }

    /** 某用户的封禁标记 */
    public static String userBanned(Long userId) {
        return USER_BANNED_PREFIX + userId;
    }
}

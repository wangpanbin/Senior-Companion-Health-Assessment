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

    /**
     * 助手问答限流：{@code assistant:rl:{userId}:{yyyyMMddHHmm}} → 次数，TTL 2 分钟。
     *
     * <p>按分钟分桶，所以桶自然过期，不需要额外的清理任务。</p>
     */
    public static final String ASSISTANT_RATE_LIMIT_PREFIX = "assistant:rl:";

    /**
     * 订单转用药计划草稿的判重标记：{@code medication:draft:from-order:{orderId}} → 1，TTL 30 分钟。
     *
     * <p>V4 · P1-2「一单一转」：同一订单在窗口内只允许生成一次草稿。</p>
     */
    public static final String MEDICATION_DRAFT_FROM_ORDER_PREFIX = "medication:draft:from-order:";

    /**
     * 健康证到期提醒的幂等标记：{@code cert:expire:remind:{profileId}:{expireDate}} → 1，TTL 30 天。
     *
     * <p>V5 · C1：同一份到期日只提醒一次；换证后到期日变化 → 键变化 → 允许再提醒一轮。</p>
     */
    public static final String CERT_EXPIRE_REMIND_PREFIX = "cert:expire:remind:";

    /**
     * 检查准备提醒的幂等标记：{@code exam-prep:remind:{orderId}:{visitDate}} → 1，TTL 48 小时。
     *
     * <p>V5 · B：同订单同就诊日只提醒一次；改期后 visitDate 变化 → 键变化 → 允许再提醒。</p>
     */
    public static final String EXAM_PREP_REMIND_PREFIX = "exam-prep:remind:";

    /* ---------- V4 · P1-1 自助找回密码短信 ---------- */

    /** 找回密码验证码：{@code sms:reset:{phone}} → 6 位数字，TTL 5 分钟 */
    public static final String SMS_RESET_CODE_PREFIX = "sms:reset:";

    /** 验证码连续输错计数：{@code sms:reset:fail:{phone}} → 次数，TTL 5 分钟 */
    public static final String SMS_RESET_FAIL_PREFIX = "sms:reset:fail:";

    /** 同手机号 60 秒发送标记：{@code sms:sent:{phone}} → 1，SET NX EX 60 */
    public static final String SMS_SEND_MARKER_PREFIX = "sms:sent:";

    /** 同手机号自然日发送计数：{@code sms:daily:{phone}:{yyyyMMdd}} → 次数，TTL 1 天 */
    public static final String SMS_DAILY_PREFIX = "sms:daily:";

    /** 同 IP 小时发送计数：{@code sms:ip:{ip}:{yyyyMMddHH}} → 次数，TTL 1 小时 */
    public static final String SMS_IP_PREFIX = "sms:ip:";

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

    /**
     * 某用户的助手问答限流计数。
     *
     * @param userId 用户 ID
     * @param minuteBucket 分钟桶，形如 {@code 202610211530}
     */
    public static String assistantRateLimit(Long userId, String minuteBucket) {
        return ASSISTANT_RATE_LIMIT_PREFIX + userId + ":" + minuteBucket;
    }

    /** 某订单的用药计划草稿判重标记 */
    public static String medicationDraftFromOrder(Long orderId) {
        return MEDICATION_DRAFT_FROM_ORDER_PREFIX + orderId;
    }

    /** 某手机号的找回密码短信验证码 */
    public static String smsResetCode(String phone) {
        return SMS_RESET_CODE_PREFIX + phone;
    }

    /** 某手机号验证码的连续输错计数 */
    public static String smsResetFail(String phone) {
        return SMS_RESET_FAIL_PREFIX + phone;
    }

    /** 某手机号的 60 秒发送标记 */
    public static String smsSendMarker(String phone) {
        return SMS_SEND_MARKER_PREFIX + phone;
    }

    /** 某手机号在指定自然日的发送计数 */
    public static String smsDailyCount(String phone, String yyyyMMdd) {
        return SMS_DAILY_PREFIX + phone + ":" + yyyyMMdd;
    }

    /** 某 IP 在指定小时的发送计数 */
    public static String smsIpHourlyCount(String ip, String yyyyMMddHH) {
        return SMS_IP_PREFIX + ip + ":" + yyyyMMddHH;
    }
}

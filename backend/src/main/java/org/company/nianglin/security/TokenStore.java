package org.company.nianglin.security;

import lombok.RequiredArgsConstructor;
import org.company.nianglin.constant.RedisKeyConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 令牌状态存储（Redis）。
 *
 * <p>承担三件事，它们共同构成了「无状态 JWT 的可控失效」：</p>
 * <ol>
 *   <li><b>登出黑名单</b>：JWT 本身无法撤销，登出时把 {@code jti} 记入黑名单，
 *       TTL 设为该令牌的剩余有效期，过期即自动清理，不会无限增长。</li>
 *   <li><b>密码版本</b>：改密 / 管理员重置密码后版本 +1，所有旧令牌的 {@code ver}
 *       立即不匹配而失效。比逐个拉黑更可靠 —— 不会漏掉任何一个已签发的令牌。</li>
 *   <li><b>登录失败计数</b>：连续失败达阈值即锁定一段时间，抵御暴力破解。</li>
 * </ol>
 *
 * @author 银龄伴诊团队
 */
@Component
@RequiredArgsConstructor
public class TokenStore {

    /** 未设置过密码版本时视为 0，与首次签发令牌时写入的 ver 一致 */
    private static final int DEFAULT_PASSWORD_VERSION = 0;

    private final StringRedisTemplate redisTemplate;

    /* ------------------------------------------------------------------ */
    /* 令牌黑名单                                                          */
    /* ------------------------------------------------------------------ */

    /**
     * 把令牌加入黑名单。
     *
     * @param jti        令牌唯一标识
     * @param ttlSeconds 剩余有效期（秒）；≤ 0 表示已过期，无需记录
     */
    public void blacklist(String jti, long ttlSeconds) {
        if (jti == null || jti.isBlank() || ttlSeconds <= 0L) {
            return;
        }
        redisTemplate.opsForValue()
                .set(RedisKeyConstants.tokenBlacklist(jti), "1", Duration.ofSeconds(ttlSeconds));
    }

    /** 令牌是否已被拉黑 */
    public boolean isBlacklisted(String jti) {
        return jti != null && Boolean.TRUE.equals(redisTemplate.hasKey(RedisKeyConstants.tokenBlacklist(jti)));
    }

    /* ------------------------------------------------------------------ */
    /* 密码版本                                                            */
    /* ------------------------------------------------------------------ */

    /** 读取当前密码版本，未设置过返回 0 */
    public int currentPasswordVersion(Long userId) {
        String value = redisTemplate.opsForValue().get(RedisKeyConstants.passwordVersion(userId));
        if (value == null) {
            return DEFAULT_PASSWORD_VERSION;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return DEFAULT_PASSWORD_VERSION;
        }
    }

    /**
     * 若密码版本键不存在则初始化为 {@code 0}。
     *
     * <p>首次登录时显式写入而非隐式回退，避免「Redis 被清空后所有旧 ver=0 令牌重新生效」
     * 这条隐蔽路径：之前 {@link #currentPasswordVersion(Long)} 对缺失键返回 0，
     * 而新签发的令牌也是 ver=0，两者刚好相等。</p>
     */
    public void ensurePasswordVersion(Long userId) {
        redisTemplate.opsForValue()
                .setIfAbsent(RedisKeyConstants.passwordVersion(userId), String.valueOf(DEFAULT_PASSWORD_VERSION));
    }

    /**
     * 密码版本 +1，所有已签发令牌立即失效。
     *
     * <p><b>使用者边界</b>：本方法是「主动全员失效」的强动作，应当且仅应当被以下场景调用：
     * 用户<b>主动</b>修改自己的密码、管理员重置用户密码、封禁账号（同步双写）、
     * 其它「我<b>就是想让这个人的全部会话立刻退出」的场景。</p>
     *
     * <p>登出接口（{@code POST /api/auth/logout}）<b>不应</b>再调用本方法——
     * 那样会让同一账号在其它设备上的会话一起被踢下线，违反「按设备登出」的
     * 用户期望（详见 {@code reports/playwright/e2e-report.md §F-01} 与
     * {@code AuthServiceImpl#logout} 的注释）。登出改走 {@link #blacklist(String, long)}，
     * 只把当前 accessToken / refreshToken 的 jti 拉黑。</p>
     */
    public int bumpPasswordVersion(Long userId) {
        Long version = redisTemplate.opsForValue().increment(RedisKeyConstants.passwordVersion(userId));
        return version == null ? DEFAULT_PASSWORD_VERSION + 1 : version.intValue();
    }

    /* ------------------------------------------------------------------ */
    /* 封禁标记（M9）                                                      */
    /* ------------------------------------------------------------------ */

    /**
     * 标记用户已被封禁。
     *
     * <p><b>为什么不只改数据库状态</b>：改库只能拦住「下次登录」，
     * 而封禁的验收要求是「任意接口请求<b>立即</b>返回 403」。
     * 用户的访问令牌最长 120 分钟，等它自然过期意味着被封禁的人
     * 还能继续下单、打卡、发消息 —— 这显然不是封禁的意思。</p>
     *
     * <p>不使用密码版本号来实现：版本号不匹配只会让请求「未认证」，
     * 返回 401 且与「登录过期」无法区分，前端只能把用户踢回登录页；
     * 而封禁要的是一个明确的 403 + 封禁原因。</p>
     *
     * <p>⚠️ 本标记与 {@code sys_user.status} 是<b>主从关系</b>：
     * 数据库是权威，标记只是让判定不必每请求查一次库。
     * Redis 被清空后标记会丢，此时靠数据库兜底（该用户重新登录会被拒），
     * 但已签发的令牌在剩余有效期内会重新生效 —— 这是可接受的降级，
     * 因为 Redis 清空本身已经是重大故障，恢复时会一并重启应用。</p>
     */
    public void markBanned(Long userId) {
        if (userId == null) {
            return;
        }
        redisTemplate.opsForValue().set(RedisKeyConstants.userBanned(userId), "1");
    }

    /** 解除封禁标记 */
    public void unmarkBanned(Long userId) {
        if (userId == null) {
            return;
        }
        redisTemplate.delete(RedisKeyConstants.userBanned(userId));
    }

    /** 用户是否处于封禁状态 */
    public boolean isBanned(Long userId) {
        return userId != null && Boolean.TRUE.equals(redisTemplate.hasKey(RedisKeyConstants.userBanned(userId)));
    }

    /* ------------------------------------------------------------------ */
    /* 登录失败计数                                                        */
    /* ------------------------------------------------------------------ */

    /**
     * 失败次数 +1。
     *
     * <p>只在第一次失败时设置 TTL，因此锁定时长是「从首次失败起算的固定窗口」，
     * 而不是「每次失败都续期」—— 后者会让攻击者永远锁不死自己，也永远刷不完窗口。</p>
     *
     * @return 累加后的失败次数
     */
    public long increaseLoginFail(String account, long lockTtlSeconds) {
        String key = RedisKeyConstants.loginFail(account);
        Long count = redisTemplate.opsForValue().increment(key);
        if (count != null && count == 1L && lockTtlSeconds > 0L) {
            redisTemplate.expire(key, Duration.ofSeconds(lockTtlSeconds));
        }
        return count == null ? 0L : count;
    }

    /** 当前窗口内的失败次数 */
    public long loginFailCount(String account) {
        String value = redisTemplate.opsForValue().get(RedisKeyConstants.loginFail(account));
        if (value == null) {
            return 0L;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /** 登录成功后清零失败计数 */
    public void clearLoginFail(String account) {
        redisTemplate.delete(RedisKeyConstants.loginFail(account));
    }
}

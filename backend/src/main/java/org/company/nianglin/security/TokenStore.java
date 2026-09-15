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

    /** 密码版本 +1，所有已签发令牌立即失效 */
    public int bumpPasswordVersion(Long userId) {
        Long version = redisTemplate.opsForValue().increment(RedisKeyConstants.passwordVersion(userId));
        return version == null ? DEFAULT_PASSWORD_VERSION + 1 : version.intValue();
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

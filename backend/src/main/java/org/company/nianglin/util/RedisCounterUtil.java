package org.company.nianglin.util;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;

/**
 * Redis 计数器工具：自增 + 首次写入时设置 TTL（原子）。
 *
 * <h3>为什么必须用 Lua</h3>
 *
 * <p>{@code INCR} 与 {@code EXPIRE} 分两次往返时，中间任何一步失败
 * （连接超时、进程被杀、Redis 重启）都会留下一个<b>永不过期的计数键</b>。
 * 对「短信日额度」「助手限流」这类计数来说，那意味着该手机号 / 该用户
 * 被<b>永久锁死</b> —— 除非有人手工去删 Redis 键。
 * Lua 脚本在 Redis 内部原子执行，{@code INCR} 与 {@code EXPIRE} 不可能只成功一半。</p>
 *
 * <h3>为什么抽成工具类而不是各处各写一份</h3>
 *
 * <p>同一段 Lua 抄两遍，改一处忘一处就会让两个调用点的语义悄悄分叉
 * （比如一个 TTL 传秒、一个传毫秒）。脚本只构造一次（{@code static final}），
 * 调用点只负责决定「Redis 不可用时怎么办」。</p>
 *
 * <p>⚠️ 本类<b>不吞异常</b>：Redis 不可用时把异常抛给调用方，由调用方决定
 * 是「放行」还是「拒绝」—— 限流与额度封顶的取舍不一样，工具层不该替它们做决定。
 * 另外本类<b>不打印 key</b>：部分 key 里含手机号等敏感片段，落进日志就是合规事故。</p>
 *
 * @author 银龄伴诊团队
 * @since V3.8
 */
public final class RedisCounterUtil {

    /** 自增并在首次写入时设置 TTL； incr 与 expire 在 Redis 内部原子完成 */
    private static final RedisScript<Long> INCREMENT_WITH_TTL_SCRIPT = new DefaultRedisScript<>(
            "local c = redis.call('incr', KEYS[1]); "
                    + "if c == 1 then redis.call('expire', KEYS[1], ARGV[1]) end; "
                    + "return c",
            Long.class);

    private RedisCounterUtil() {
    }

    /**
     * 自增计数，并在首次写入（计数由 0 变 1）时设置 TTL。
     *
     * <p>窗口内的后续自增<b>不刷新 TTL</b>：窗口是「从第一次触发起算的固定区间」，
     * 每次自增都续期会让计数永远达不到上限（限流失效）。</p>
     *
     * @param redis Redis 模板
     * @param key   计数键
     * @param ttl   窗口时长（秒级精度）
     * @return 累加后的计数；脚本返回 null 时按 0 处理
     */
    public static long incrementWithTtl(StringRedisTemplate redis, String key, Duration ttl) {
        Long count = redis.execute(INCREMENT_WITH_TTL_SCRIPT, List.of(key), String.valueOf(ttl.toSeconds()));
        return count == null ? 0L : count;
    }
}

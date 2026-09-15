package org.company.nianglin.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * 基于 Redis 的轻量分布式锁（{@code SET NX PX} + Lua 释放）。
 *
 * <h3>为什么不用 Redisson</h3>
 *
 * <p>开发环境的 Redis 是 Windows 移植版，Redisson 的部分高级特性（看门狗续期、
 * Lua 之外的脚本通道）在这类环境下行为不稳定。本项目的锁只有一个用途 ——
 * 「同时起了两个后端实例时，定时任务只允许一个真正执行」——
 * 这个场景对锁的要求很低：<b>宁可漏执行一次（下一次扫描会补上），
 * 也绝不能两个实例同时执行</b>。{@code SET NX PX} 恰好满足这个不对称性。</p>
 *
 * <h3>释放锁为什么必须用 Lua 比对 uuid</h3>
 *
 * <p>直接 {@code DEL key} 会删掉别人的锁：A 拿到锁 → A 的任务超时（或 GC 停顿）
 * → 锁自动过期 → B 拿到锁 → A 任务结束回来 {@code DEL}，
 * 删掉的是 <b>B 的锁</b>。此后 C 也能拿到锁，临界区里同时有两个实例。
 * 比对 uuid 之后，A 只在「锁还是我自己的」时才删，异常路径安全。</p>
 *
 * <p>注意：本类<b>不做锁续期</b>。因为定时任务的执行时间远小于锁的持有时间
 * （默认 5 分钟），而续期逻辑一旦写错，代价是死锁 —— 得不偿失。</p>
 *
 * @author 银龄伴诊团队
 * @since M6
 */
@Slf4j
public final class RedisLockUtil {

    /** 仅当值等于调用方持有的 uuid 时才删除 —— 这一行是「不删别人的锁」的全部实现 */
    private static final RedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private RedisLockUtil() {
    }

    /**
     * 持有锁的句柄。用 try-with-resources 保证任何异常路径都会走到释放。
     *
     * <pre>
     * try (RedisLockUtil.Lock lock = RedisLockUtil.tryLock(redis, "medication:daily", Duration.ofMinutes(5))) {
     *     if (!lock.acquired()) { return; }
     *     // 临界区
     * }
     * </pre>
     */
    public static final class Lock implements AutoCloseable {

        private final StringRedisTemplate redis;
        private final String key;
        private final String token;
        private final boolean acquired;
        private boolean closed;

        private Lock(StringRedisTemplate redis, String key, String token, boolean acquired) {
            this.redis = redis;
            this.key = key;
            this.token = token;
            this.acquired = acquired;
        }

        /** 是否真的拿到了锁 */
        public boolean acquired() {
            return acquired;
        }

        @Override
        public void close() {
            if (!acquired || closed) {
                return;
            }
            closed = true;
            try {
                Long released = redis.execute(RELEASE_SCRIPT, List.of(key), token);
                if (released == null || released == 0L) {
                    // 走到这里说明锁已经因为超时被别人拿走了 —— 不是错误，
                    // 但要留痕：如果频繁出现，说明锁的 TTL 需要调大
                    log.warn("锁已易主或过期，未执行释放 | key={}", key);
                }
            } catch (Exception e) {
                // 释放失败不能向上抛：它通常发生在 finally 里，
                // 抛出会盖掉业务异常，反而丢失真正的失败原因
                log.warn("锁释放失败（不影响业务） | key={} | {}", key, e.getMessage());
            }
        }
    }

    /**
     * 尝试加锁，不等待。
     *
     * <p>不做自旋等待是刻意的：定时任务的重叠执行不是「需要排队」，
     * 而是「另一个实例正在跑，这次跳过即可」。</p>
     *
     * @param biz      业务名，如 {@code medication:daily}
     * @param ttl      锁自动过期时间，必须显著大于任务最长执行时间
     * @return 锁句柄，{@link Lock#acquired()} 为 {@code false} 表示未取到
     */
    public static Lock tryLock(StringRedisTemplate redis, String biz, Duration ttl) {
        String key = org.company.nianglin.constant.RedisKeyConstants.lock(biz);
        String token = UUID.randomUUID().toString().replace("-", "");
        boolean acquired = false;
        try {
            acquired = Boolean.TRUE.equals(
                    redis.opsForValue().setIfAbsent(key, token, ttl));
        } catch (Exception e) {
            // Redis 不可用时不阻塞业务：定时任务本身是可重入的幂等操作，
            // 「因为 Redis 挂了就完全不生成服药任务」比「可能重复生成」糟糕得多
            log.error("获取分布式锁异常，本次按未取到处理 | key={} | {}", key, e.getMessage());
        }
        if (!acquired) {
            log.info("未取到分布式锁，跳过本次执行 | key={}", key);
        }
        return new Lock(redis, key, token, acquired);
    }
}

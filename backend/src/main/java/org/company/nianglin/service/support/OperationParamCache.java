package org.company.nianglin.service.support;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * 运营参数的进程内 TTL 快照缓存。
 *
 * <h3>为什么是进程内而不是 Redis</h3>
 *
 * <p>参数读多写极少、总量 4 条，一次全量快照的代价可以忽略。
 * 进程内快照不引入网络依赖：Redis 挂掉时业务仍按上一份快照或
 * {@code application.yml} 默认值运行，不会因为「读参数」把主流程拖垮。</p>
 *
 * <h3>失效策略（与 spec §3.2 一致）</h3>
 *
 * <ul>
 *   <li><b>命中</b>：TTL 内直接返回快照，不查库；</li>
 *   <li><b>TTL 到期</b>：下一次读取由 Service 同步重载一次（{@code synchronized} 防击穿）；</li>
 *   <li><b>管理端修改</b>：事务提交后立即 {@link #putAll(Map)} 刷新；</li>
 *   <li><b>读库失败</b>：保留 {@link #snapshot()} 的上一份值，由 Service 回落；</li>
 *   <li><b>多实例</b>：其他实例最多一个 TTL（默认 60s）后收敛。</li>
 * </ul>
 *
 * @author 银龄伴诊团队
 * @since V4 · P2-3
 */
@Component
public class OperationParamCache {

    /** 默认 TTL：60 秒；多实例部署时的最大收敛窗口 */
    public static final long DEFAULT_TTL_MILLIS = 60_000L;

    private final long ttlMillis;
    private final LongSupplier clock;

    private volatile Map<String, String> snapshot = Collections.emptyMap();
    private volatile long loadedAtMillis = Long.MIN_VALUE;

    @Autowired
    public OperationParamCache(
            @Value("${nianglin.operation-param.cache-ttl-ms:60000}") long ttlMillis) {
        this(ttlMillis, System::currentTimeMillis);
    }

    /** 单测专用：注入可推进的时钟 */
    OperationParamCache(long ttlMillis, LongSupplier clock) {
        this.ttlMillis = ttlMillis > 0 ? ttlMillis : DEFAULT_TTL_MILLIS;
        this.clock = clock;
    }

    /**
     * 取缓存值；快照已过期时返回 {@code null}，由调用方触发重载。
     */
    public String get(String code) {
        if (!isFresh()) {
            return null;
        }
        return snapshot.get(code);
    }

    /** 快照是否仍在 TTL 内 */
    public boolean isFresh() {
        long loaded = loadedAtMillis;
        if (loaded == Long.MIN_VALUE) {
            return false;
        }
        long now = clock.getAsLong();
        return now >= loaded && now - loaded <= ttlMillis;
    }

    /**
     * 最近一次成功加载的原始快照（不判断 TTL）。
     *
     * <p>读库失败时 Service 用它保留上一份可用值，而不是立刻退回默认值。</p>
     */
    public Map<String, String> snapshot() {
        return snapshot;
    }

    /** 整份替换并刷新时间戳；{@code null} 值会被丢弃 */
    public void putAll(Map<String, String> values) {
        Map<String, String> copy = new HashMap<>();
        if (values != null) {
            values.forEach((key, value) -> {
                if (key != null && value != null) {
                    copy.put(key, value);
                }
            });
        }
        snapshot = Collections.unmodifiableMap(copy);
        loadedAtMillis = clock.getAsLong();
    }

    /** 清空快照：下一次读取必然回源 */
    public void invalidate() {
        snapshot = Collections.emptyMap();
        loadedAtMillis = Long.MIN_VALUE;
    }

    /** 仅用于日志输出 TTL，避免测试断言依赖内部常量 */
    public long getTtlMillis() {
        return ttlMillis;
    }
}

package org.company.nianglin.service.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 运营参数缓存的三条性质：命中、TTL 到期、显式失效。
 *
 * <p>用可推进的时钟而不是 {@code Thread.sleep}：TTL 测试必须确定、快，
 * 且不能因为 CI 机器慢而偶发。{@link OperationParamCache#snapshot()} 在过期后仍保留
 * 上一份值，这是「读库失败回落快照」的实现基础，也在下面被锁死。</p>
 *
 * @author 银龄伴诊团队
 * @since V4 · P2-3
 */
@DisplayName("运营参数缓存：命中 / TTL / 失效")
class OperationParamCacheTest {

    private static final long TTL = 60_000L;

    private final AtomicLong now = new AtomicLong(1_000L);

    private OperationParamCache cache() {
        return new OperationParamCache(TTL, now::get);
    }

    @Test
    @DisplayName("TTL 内命中：值可读且快照新鲜")
    void shouldHitWithinTtl() {
        OperationParamCache cache = cache();
        cache.putAll(Map.of("k", "45"));

        assertThat(cache.isFresh()).isTrue();
        assertThat(cache.get("k")).isEqualTo("45");

        now.addAndGet(TTL - 1);
        assertThat(cache.isFresh()).isTrue();
        assertThat(cache.get("k")).isEqualTo("45");
    }

    @Test
    @DisplayName("TTL 到期：get 返回 null 触发回源，但 snapshot 仍保留上一份值")
    void shouldExpireAfterTtlButKeepSnapshot() {
        OperationParamCache cache = cache();
        cache.putAll(Map.of("k", "45"));

        now.addAndGet(TTL + 1);

        assertThat(cache.isFresh()).isFalse();
        assertThat(cache.get("k")).isNull();
        // 保留上一份快照：读库失败时 Service 用它兜底，而不是立刻退回 YAML 默认值
        assertThat(cache.snapshot()).containsEntry("k", "45");
    }

    @Test
    @DisplayName("显式失效：立即不可命中，snapshot 清空")
    void shouldMissAfterInvalidate() {
        OperationParamCache cache = cache();
        cache.putAll(Map.of("k", "45"));

        cache.invalidate();

        assertThat(cache.isFresh()).isFalse();
        assertThat(cache.get("k")).isNull();
        assertThat(cache.snapshot()).isEmpty();
    }

    @Test
    @DisplayName("putAll 过滤 null 值，避免字典里的空值把整份快照写坏")
    void shouldIgnoreNullEntries() {
        OperationParamCache cache = cache();
        Map<String, String> values = new java.util.HashMap<>();
        values.put("k", null);
        values.put("k2", "1");

        cache.putAll(values);

        assertThat(cache.get("k2")).isEqualTo("1");
        assertThat(cache.snapshot()).doesNotContainKey("k");
    }
}

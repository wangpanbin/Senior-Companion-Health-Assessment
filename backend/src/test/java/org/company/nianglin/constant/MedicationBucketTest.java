package org.company.nianglin.constant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 服药三桶划分单测（V3.2 · T-0 · FR-Bucket）。
 *
 * <p>这是「漏服」这件事的<b>唯一真相表</b>。它刻意不加载 Spring 上下文、
 * 不碰数据库、不 mock 时钟 —— 跑一次是毫秒级，因此可以把每一种状态组合
 * 全部覆盖，而不必挑选几条凑数。</p>
 *
 * <h3>为什么这张表必须被锁住</h3>
 *
 * <p>仓库里「漏服」曾经有两套判定，一套只认 {@code was_missed = 1}，
 * 一套认 {@code was_missed = 1} 或 {@code status = 'MISSED'}。
 * 漏服扫描只写 {@code status}、从不写 {@code was_missed}，
 * 于是按第一套口径统计时，那批任务<b>既不 taken 也不 missed</b>，
 * {@code taken + missed ≠ total}。本表把唯一真源钉死，
 * 配套的 {@code MedicationBucketParityTest} 保证 SQL 侧与它一致。</p>
 *
 * @author 银龄伴诊团队
 * @since V3.2
 */
@DisplayName("服药三桶划分：漏服优先 / 三桶闭合 / 脏数据防御")
class MedicationBucketTest {

    /* ================================================================== */
    /* 划分表                                                              */
    /* ================================================================== */

    @Nested
    @DisplayName("划分表")
    class Buckets {

        @ParameterizedTest(name = "status={0} wasMissed={1} → {2}")
        @CsvSource({
                // 一直没吃，被扫描判成漏服（was_missed 从没被写过）
                "MISSED,  false, MISSED",
                // 一直没吃 + 补记痕迹都有
                "MISSED,  true,  MISSED",
                // 漏了之后被补记：status 已变成 TAKEN，只有 was_missed 还留着痕迹
                "TAKEN,   true,  MISSED",
                // 正常吃了，且从未漏过
                "TAKEN,   false, TAKEN",
                // 还没到确认时点
                "PENDING, false, PENDING",
                // 待服 + 曾漏服：漏服优先
                "PENDING, true,  MISSED",
        })
        @DisplayName("状态组合 → 桶")
        void statusAndWasMissedShouldDecideBucket(String status, boolean wasMissed, MedicationBucket expected) {
            assertSame(expected, MedicationBucket.of(status, wasMissed));
        }

        @ParameterizedTest(name = "wasMissed=null 时 status={0} → {1}")
        @CsvSource({
                "TAKEN,   TAKEN",
                "MISSED,  MISSED",
                "PENDING, PENDING",
        })
        @DisplayName("wasMissed 为 null 按未漏服处理")
        void nullWasMissedShouldBeTreatedAsNotMissed(String status, MedicationBucket expected) {
            assertSame(expected, MedicationBucket.of(status, null));
        }
    }

    /* ================================================================== */
    /* 脏数据防御                                                          */
    /* ================================================================== */

    @Nested
    @DisplayName("脏数据防御")
    class DirtyData {

        @ParameterizedTest(name = "未知 status={0} → 兜底 TAKEN（不抛异常）")
        @NullAndEmptySource
        @ValueSource(strings = {"UNKNOWN", "taken", "MISSED "})
        @DisplayName("状态值越界 → 兜底已服，不抛异常")
        void dirtyStatusShouldFallbackToTaken(String status) {
            assertSame(MedicationBucket.TAKEN, MedicationBucket.of(status, false));
        }

        @Test
        @DisplayName("status 与 wasMissed 都为 null → 兜底已服")
        void nullStatusAndFlagShouldFallbackToTaken() {
            assertSame(MedicationBucket.TAKEN, MedicationBucket.of(null, null));
        }
    }

    /* ================================================================== */
    /* 三桶闭合                                                            */
    /* ================================================================== */

    @Test
    @DisplayName("三桶互斥且穷尽：任一状态组合只落进一个桶")
    void bucketsShouldBeMutuallyExclusiveAndExhaustive() {
        String[] statuses = {"TAKEN", "MISSED", "PENDING", "UNKNOWN", null};
        for (String status : statuses) {
            for (Boolean wasMissed : new Boolean[]{Boolean.TRUE, Boolean.FALSE, null}) {
                MedicationBucket bucket = MedicationBucket.of(status, wasMissed);
                assertNotNull(bucket, "判定结果不允许为 null");
                // 穷尽性：三个桶之外不存在第四个可能值
                assertTrue(bucket == MedicationBucket.TAKEN
                                || bucket == MedicationBucket.MISSED
                                || bucket == MedicationBucket.PENDING,
                        "判定结果必须落在三个桶之内");
            }
        }
    }

    @Test
    @DisplayName("枚举名必须与 SQL 字面量一致（SQL 侧靠它对齐）")
    void enumNameShouldMatchSqlLiteral() {
        assertEquals(MedicationBucketSql.CODE_TAKEN, MedicationBucket.TAKEN.name());
        assertEquals(MedicationBucketSql.CODE_MISSED, MedicationBucket.MISSED.name());
        assertEquals(MedicationBucketSql.CODE_PENDING, MedicationBucket.PENDING.name());
    }
}

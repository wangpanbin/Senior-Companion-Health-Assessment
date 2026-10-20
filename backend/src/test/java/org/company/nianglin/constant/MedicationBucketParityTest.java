package org.company.nianglin.constant;

import org.apache.ibatis.annotations.Select;
import org.company.nianglin.mapper.StatisticsMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用药三桶的<b>口径一致性</b>闸门（V3.2 · T-0 · FR-Bucket）。
 *
 * <h3>这个测试在防什么</h3>
 *
 * <p>「漏服」曾经有两套判定：Java 侧一份（{@code MedicationCalendarVO}），
 * SQL 侧一份（{@code StatisticsMapper}），两边各自演化，最后对不上 ——
 * 而且两处注释都宣称「口径一致」，那两句话是错的。</p>
 *
 * <p>本测试<b>不</b>靠注释约束，而是直接反射读出真正下发的 SQL 文本，
 * 检查三件事：</p>
 * <ol>
 *   <li>SQL 里的三桶判定<b>只出现一次</b>（就是共享的 {@code CASE_BUCKET}）；</li>
 *   <li>taken / missed / pending 三个计数全部从<b>同一个</b> {@code bucket} 派生；</li>
 *   <li>旧的「{@code was_missed = 1} 单独判漏服」写法已经不在 SQL 里。</li>
 * </ol>
 *
 * <p>任何一边单独改动而另一边没跟上，这里立刻变红。</p>
 *
 * @author 银龄伴诊团队
 * @since V3.2
 */
@DisplayName("用药三桶口径一致性：Java 唯一真源 vs SQL 共享表达式")
class MedicationBucketParityTest {

    /** M10 漏服统计查询的方法签名 */
    private static final String METHOD = "selectDailyMedicationCounts";

    /* ================================================================== */
    /* 1 · SQL 里只有一个分类点                                             */
    /* ================================================================== */

    @Test
    @DisplayName("M10 漏服 SQL：共享 CASE 表达式只出现一次")
    void dailyMedicationSqlShouldEmbedCaseBucketExactlyOnce() {
        String sql = dailyMedicationSql();

        assertEquals(1, countOccurrences(sql, MedicationBucketSql.CASE_BUCKET),
                "共享的三桶判定表达式必须在 SQL 中恰好出现一次 —— "
                        + "出现多次就说明有人把判定手写进了 SQL 旁支，口径迟早会漂移");
    }

    @Test
    @DisplayName("M10 漏服 SQL：三个计数都从同一个 bucket 字段派生")
    void dailyMedicationSqlShouldDeriveAllBucketsFromBucketColumn() {
        String sql = dailyMedicationSql();

        assertTrue(sql.contains("SUM(CASE WHEN bucket = 'TAKEN' THEN 1 ELSE 0 END) AS taken"),
                "taken 必须从已算好的 bucket 派生");
        assertTrue(sql.contains("SUM(CASE WHEN bucket = 'MISSED' THEN 1 ELSE 0 END) AS missed"),
                "missed 必须从已算好的 bucket 派生");
        assertTrue(sql.contains("SUM(CASE WHEN bucket = 'PENDING' THEN 1 ELSE 0 END) AS pending"),
                "pending 必须从已算好的 bucket 派生");
    }

    @Test
    @DisplayName("M10 漏服 SQL：旧的「was_missed=1 单独判漏服」写法已清除")
    void dailyMedicationSqlShouldNotKeepLegacyMissedRule() {
        String sql = dailyMedicationSql();

        assertFalse(sql.contains("SUM(CASE WHEN was_missed = 1 THEN 1 ELSE 0 END)"),
                "旧写法会把「扫描判定的漏服」整批漏掉（taken + missed ≠ total），必须清除");
    }

    /* ================================================================== */
    /* 2 · CASE 表达式本身的结构                                           */
    /* ================================================================== */

    @Test
    @DisplayName("共享 CASE 表达式：三个桶的字面量齐备且顺序为 漏服 → 待服 → 已服")
    void caseBucketShouldCoverAllBucketsInRuleOrder() {
        String expression = MedicationBucketSql.CASE_BUCKET;

        int missedAt = expression.indexOf("'" + MedicationBucketSql.CODE_MISSED + "'");
        int pendingAt = expression.indexOf("'" + MedicationBucketSql.CODE_PENDING + "'");
        int takenAt = expression.indexOf("'" + MedicationBucketSql.CODE_TAKEN + "'");

        assertTrue(missedAt >= 0, "CASE 表达式必须包含漏服桶");
        assertTrue(pendingAt >= 0, "CASE 表达式必须包含待服桶");
        assertTrue(takenAt >= 0, "CASE 表达式必须包含已服桶");
        assertTrue(missedAt < pendingAt && pendingAt < takenAt,
                "判定顺序必须与 Java 侧一致：漏服优先，其次待服，其余已服 —— "
                        + "顺序反了会把补记过的任务算成已服，漏服率被稀释");
    }

    @Test
    @DisplayName("共享 CASE 表达式：漏服条件同时覆盖 status=MISSED 与 was_missed=1")
    void caseBucketShouldCoverBothMissedSignals() {
        String expression = MedicationBucketSql.CASE_BUCKET;

        assertTrue(expression.contains("was_missed = 1"),
                "漏服判定必须包含 was_missed = 1（漏服后补记的痕迹）");
        assertTrue(expression.contains("status = '" + MedicationBucketSql.CODE_MISSED + "'"),
                "漏服判定必须包含 status = 'MISSED'（扫描判定的漏服）");
    }

    /* ================================================================== */
    /* 工具                                                                */
    /* ================================================================== */

    /** 反射读出真正下发给 MyBatis 的 SQL 文本 */
    private String dailyMedicationSql() {
        try {
            Method method = StatisticsMapper.class.getMethod(METHOD,
                    LocalDateTime.class, LocalDateTime.class, Long.class);
            Select select = method.getAnnotation(Select.class);
            assertTrue(select != null, METHOD + " 必须是 @Select 原生 SQL");
            return String.join(" ", select.value());
        } catch (NoSuchMethodException e) {
            throw new AssertionError("找不到 StatisticsMapper#" + METHOD + "，口径闸门失效", e);
        }
    }

    private int countOccurrences(String haystack, String needle) {
        int count = 0;
        int from = 0;
        while (true) {
            int at = haystack.indexOf(needle, from);
            if (at < 0) {
                return count;
            }
            count++;
            from = at + needle.length();
        }
    }
}

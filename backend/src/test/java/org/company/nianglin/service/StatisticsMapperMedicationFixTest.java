package org.company.nianglin.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.company.nianglin.constant.MedicationBucket;
import org.company.nianglin.entity.MedicationTask;
import org.company.nianglin.mapper.MedicationTaskMapper;
import org.company.nianglin.mapper.StatisticsMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M10 漏服口径修正的真库 parity（V3.2 · T-0 · FR-Bucket）。
 *
 * <h3>这个测试在防什么</h3>
 *
 * <p>V3.2 之前，M10 的漏服统计只认 {@code was_missed = 1}，
 * 而漏服扫描任务只写 {@code status = 'MISSED'}、<b>从不写</b> {@code was_missed}。
 * 结果是那批任务在统计里<b>既不计入已服、也不计入漏服</b> ——
 * {@code taken + missed ≠ total}，数字自己对不上账。</p>
 *
 * <p>本测试拿真库的服药任务做一次「SQL 聚合 vs Java 逐条分桶」的对照：
 * 两侧必须给出完全相同的三个桶。它是 {@code MedicationBucketParityTest}
 * （结构层闸门）的运行期补充 —— 结构闸门保证 SQL 里只有一个分类点，
 * 本测试保证那个分类点在真数据上算出来的数就是对的。</p>
 *
 * <p><b>只读</b>：全程不写库，避免污染种子数据与 E2E 的夹具回滚基线。</p>
 *
 * @author 银龄伴诊团队
 * @since V3.2
 */
@SpringBootTest
@DisplayName("M10 漏服口径修正：SQL 三桶 == Java 三桶（真库 parity）")
class StatisticsMapperMedicationFixTest {

    /** 覆盖全部服药任务的时间窗 */
    private static final LocalDateTime START = LocalDateTime.of(2020, 1, 1, 0, 0, 0);
    private static final LocalDateTime END = LocalDateTime.of(2030, 12, 31, 23, 59, 59);

    @Autowired
    private StatisticsMapper statisticsMapper;

    @Autowired
    private MedicationTaskMapper medicationTaskMapper;

    /* ================================================================== */
    /* 1 · SQL 与 Java 逐桶一致                                            */
    /* ================================================================== */

    @Test
    @DisplayName("SQL 聚合的三个桶 == Java 逐条分桶的三个桶")
    void sqlBucketsShouldEqualJavaBuckets() {
        long[] java = javaBuckets();
        long[] sql = sqlBuckets();

        assertEquals(java[0], sql[0], "已服数不一致：SQL 与 Java 分桶对不上");
        assertEquals(java[1], sql[1], "漏服数不一致：SQL 与 Java 分桶对不上");
        assertEquals(java[2], sql[2], "待服数不一致：SQL 与 Java 分桶对不上");
        assertEquals(java[3], sql[3], "总数不一致");
    }

    /* ================================================================== */
    /* 2 · 三桶闭合                                                        */
    /* ================================================================== */

    @Test
    @DisplayName("三桶之和 == 总数（旧口径下这条会直接失败）")
    void bucketsShouldSumToTotal() {
        long[] sql = sqlBuckets();
        assertEquals(sql[3], sql[0] + sql[1] + sql[2],
                "三桶必须构成划分：已服 + 漏服 + 待服 == 总数");
    }

    /* ================================================================== */
    /* 3 · 扫描判定的漏服必须被计入                                        */
    /* ================================================================== */

    @Test
    @DisplayName("漏服数必须覆盖全部「扫描判定的漏服」行（status=MISSED 且 was_missed=0）")
    void missedShouldCoverScannedMissedRows() {
        long scannedMissed = medicationTaskMapper.selectCount(Wrappers.<MedicationTask>lambdaQuery()
                .eq(MedicationTask::getStatus, "MISSED")
                .and(w -> w.isNull(MedicationTask::getWasMissed).or().eq(MedicationTask::getWasMissed, 0)));

        long[] sql = sqlBuckets();
        assertTrue(sql[1] >= scannedMissed,
                "漏服数必须覆盖全部扫描判定的漏服行（实际漏服数=" + sql[1]
                        + "，扫描判定漏服行数=" + scannedMissed + "）");
    }

    /* ================================================================== */
    /* 取数                                                                */
    /* ================================================================== */

    /** Java 侧逐条分桶：{已服, 漏服, 待服, 总数} */
    private long[] javaBuckets() {
        List<MedicationTask> rows = medicationTaskMapper.selectList(
                Wrappers.<MedicationTask>lambdaQuery()
                        .ge(MedicationTask::getPlanTime, START)
                        .le(MedicationTask::getPlanTime, END));

        long[] buckets = new long[4];
        for (MedicationTask row : rows) {
            buckets[3]++;
            MedicationBucket bucket = MedicationBucket.of(
                    row.getStatus(),
                    row.getWasMissed() != null && row.getWasMissed() == 1);
            switch (bucket) {
                case TAKEN -> buckets[0]++;
                case MISSED -> buckets[1]++;
                case PENDING -> buckets[2]++;
            }
        }
        return buckets;
    }

    /** SQL 侧聚合：{已服, 漏服, 待服, 总数} */
    private long[] sqlBuckets() {
        long[] buckets = new long[4];
        for (Map<String, Object> row : statisticsMapper.selectDailyMedicationCounts(START, END, null)) {
            buckets[0] += longOf(row.get("taken"));
            buckets[1] += longOf(row.get("missed"));
            buckets[2] += longOf(row.get("pending"));
            buckets[3] += longOf(row.get("total"));
        }
        return buckets;
    }

    private long longOf(Object value) {
        return value == null ? 0L : ((Number) value).longValue();
    }
}

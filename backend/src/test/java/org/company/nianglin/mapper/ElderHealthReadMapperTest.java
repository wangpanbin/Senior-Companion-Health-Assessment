package org.company.nianglin.mapper;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.company.nianglin.constant.MedicationBucket;
import org.company.nianglin.entity.CompanionOrder;
import org.company.nianglin.entity.MedicationTask;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 老人健康聚合查询的真库校验（V3.2 · FR-Overview / FR-Summary）。
 *
 * <h3>为什么这些查询必须上真库验</h3>
 *
 * <p>它们是 V3.2 唯一的 SQL 层：{@code GROUP BY + 派生表 + 共享 CASE 表达式}。
 * 这类写法在 Mockito 单测里永远是「绿的」—— 因为根本没碰到数据库。
 * 只有真库能证明：</p>
 * <ul>
 *   <li>派生表 + {@code CASE_BUCKET} 的 SQL 真的能跑（语法、索引、类型）；</li>
 *   <li>三桶之和真的等于总数；</li>
 *   <li>聚合口径真的等于逐条分桶。</li>
 * </ul>
 *
 * <p><b>只读</b>：全程不写库，避免污染种子数据与 E2E 的夹具回滚基线。</p>
 *
 * @author 银龄伴诊团队
 * @since V3.2
 */
@SpringBootTest
@DisplayName("老人健康聚合查询：三桶闭合 / 口径一致 / 已完成判定")
class ElderHealthReadMapperTest {

    /** fam001 名下的老人（张德海） */
    private static final Long ELDER_ID = 401L;

    /** fam001 */
    private static final Long FAMILY_ID = 101L;

    /** 覆盖种子数据的时间窗（种子的用药任务落在 2026-08-20 ~ 2026-09-16） */
    private static final LocalDate START = LocalDate.of(2026, 8, 1);
    private static final LocalDate END = LocalDate.of(2026, 9, 30);

    @Autowired
    private ElderHealthReadMapper healthReadMapper;

    @Autowired
    private MedicationTaskMapper medicationTaskMapper;

    @Autowired
    private CompanionOrderMapper companionOrderMapper;

    /* ================================================================== */
    /* 1 · 家属名下老人：只含有效绑定                                        */
    /* ================================================================== */

    @Test
    @DisplayName("家属名下老人只含 status=BOUND 的绑定（已解绑的不出现）")
    void familyEldersShouldOnlyContainBoundRelations() {
        List<Map<String, Object>> rows = healthReadMapper.selectFamilyElders(FAMILY_ID);

        assertNotNull(rows);
        assertFalse(rows.isEmpty(), "fam001 至少应有一位绑定的老人");
        assertTrue(rows.stream().allMatch(row -> Long.valueOf(401L).equals(longOf(row.get("elderId")))),
                "fam001 绑定的有效老人应为 401，解绑的那位不得出现");
    }

    /* ================================================================== */
    /* 2 · 用药三桶                                                        */
    /* ================================================================== */

    @Test
    @DisplayName("用药三桶之和 == 总数")
    void medicationBucketsShouldSumToTotal() {
        Map<String, Object> row = first(healthReadMapper.selectMedicationBucketsByElder(
                List.of(ELDER_ID), START, END));

        long total = longOf(row.get("total"));
        long taken = longOf(row.get("taken"));
        long missed = longOf(row.get("missed"));
        long pending = longOf(row.get("pending"));

        assertEquals(total, taken + missed + pending, "三桶必须构成划分");
        assertTrue(total > 0, "该时间窗内应能取到用药任务");
    }

    @Test
    @DisplayName("聚合三桶 == Java 逐条分桶")
    void aggregateBucketsShouldEqualJavaBuckets() {
        Map<String, Object> row = first(healthReadMapper.selectMedicationBucketsByElder(
                List.of(ELDER_ID), START, END));

        List<MedicationTask> tasks = medicationTaskMapper.selectList(
                Wrappers.<MedicationTask>lambdaQuery()
                        .eq(MedicationTask::getElderId, ELDER_ID)
                        .ge(MedicationTask::getPlanDate, START)
                        .le(MedicationTask::getPlanDate, END));

        long taken = 0;
        long missed = 0;
        long pending = 0;
        for (MedicationTask task : tasks) {
            MedicationBucket bucket = MedicationBucket.of(task.getStatus(),
                    task.getWasMissed() != null && task.getWasMissed() == 1);
            switch (bucket) {
                case TAKEN -> taken++;
                case MISSED -> missed++;
                case PENDING -> pending++;
            }
        }

        assertEquals(taken, longOf(row.get("taken")), "已服数不一致");
        assertEquals(missed, longOf(row.get("missed")), "漏服数不一致");
        assertEquals(pending, longOf(row.get("pending")), "待服数不一致");
        assertEquals(tasks.size(), longOf(row.get("total")), "总数不一致");
    }

    /* ================================================================== */
    /* 3 · 就医统计：已完成判定                                            */
    /* ================================================================== */

    @Test
    @DisplayName("就医次数只算 COMPLETED + REVIEWED，未接单的不计入")
    void visitStatsShouldOnlyCountFinishedOrders() {
        LocalDateTime start = START.atStartOfDay();
        LocalDateTime end = END.atTime(23, 59, 59);

        Map<String, Object> row = first(healthReadMapper.selectVisitStatsByElder(
                List.of(ELDER_ID), start, end));

        long orderCount = longOf(row.get("orderCount"));
        long companionCount = longOf(row.get("companionCount"));

        // 种子给 401 号老人留了 1 单待接单 + 1 单已评价，已完成的只有后者
        assertEquals(1L, orderCount, "只有已完成的订单才计入就医次数");
        assertEquals(1L, companionCount, "服务过的陪诊员人数");
        assertNotNull(row.get("lastVisitAt"), "最近一次已完成陪诊的时间");
        assertNotNull(row.get("lastActivityAt"), "最近一次就诊的时间");
    }

    /* ================================================================== */
    /* 4 · 待处理事项计数                                                  */
    /* ================================================================== */

    @Test
    @DisplayName("待处理计数：待接单、待评价、高风险各自独立")
    void pendingCountsShouldBeIndependent() {
        Map<String, Object> row = first(healthReadMapper.selectPendingCountsByElder(List.of(ELDER_ID)));

        long waiting = longOf(row.get("waitingOrders"));
        long toReview = longOf(row.get("toReviewOrders"));
        long risk = longOf(row.get("riskOrders"));

        assertEquals(1L, waiting, "待接单订单数");
        assertEquals(0L, toReview, "已评价的订单不算待评价");
        assertTrue(risk <= waiting, "高风险订单必然是待接单订单的子集");

        // ⚠️ 不能把 riskOrders 写成固定值：FulfillmentScheduler 每 5 分钟扫一次
        // PENDING 订单并升级风险，种子里的待接单订单会随运行时间从 NORMAL 升到
        // URGENT / MANUAL_REQUIRED。所以这里只断言「口径对」：
        // 高风险数必须等于「待接单 且 风险已达高位」的订单数。
        long expectedRisk = companionOrderMapper.selectCount(
                Wrappers.<CompanionOrder>lambdaQuery()
                        .eq(CompanionOrder::getElderId, ELDER_ID)
                        .eq(CompanionOrder::getStatus, "PENDING")
                        .in(CompanionOrder::getFulfillmentRisk, "URGENT", "MANUAL_REQUIRED"));
        assertEquals(expectedRisk, risk, "高风险订单数口径不符");
    }

    /* ================================================================== */
    /* 工具                                                                */
    /* ================================================================== */

    private Map<String, Object> first(List<Map<String, Object>> rows) {
        assertNotNull(rows);
        assertFalse(rows.isEmpty(), "查询应至少返回一行");
        return rows.get(0);
    }

    private static long longOf(Object value) {
        return value == null ? 0L : ((Number) value).longValue();
    }
}

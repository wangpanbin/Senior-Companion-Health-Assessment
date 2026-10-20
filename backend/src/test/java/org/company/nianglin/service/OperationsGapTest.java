package org.company.nianglin.service;

import org.company.nianglin.config.OperationsProperties;
import org.company.nianglin.dto.OperationsGapQuery;
import org.company.nianglin.exception.BusinessException;
import org.company.nianglin.mapper.OperationsMapper;
import org.company.nianglin.service.impl.OperationsServiceImpl;
import org.company.nianglin.service.support.metrics.MetricCalculatorRegistry;
import org.company.nianglin.vo.OperationsGapVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 供给时段缺口分析的口径单测（V3.6 · T4 · T7）。
 *
 * <h3>这个测试在防什么</h3>
 *
 * <ol>
 *   <li><b>桶的边界算错</b>：周日是 7 不是 0，小时有 0 和 23；</li>
 *   <li><b>同一个人被重复计数</b>：他有多少个时段就在多少个桶里各计 1 次，
 *       但<b>每个桶里只计 1 次</b>（不是按单数计）；</li>
 *   <li><b>空桶缺行</b>：没有数据的时段也要有一行（计数为 0），
 *       否则前端的热力网格会出现空洞；</li>
 *   <li><b>缺口符号反了</b>：缺口 = 需求 − 供给，负数是「供给多于需求」。</li>
 * </ol>
 *
 * <h3>为什么供给数按「可服务时段」分桶</h3>
 *
 * <p>偏差 D-3.6-1：如果按「当前工作状态为 AVAILABLE 的总人数」算，
 * 每个桶的供给数都是同一个值，这个指标毫无意义。</p>
 *
 * @author 银龄伴诊团队
 * @since V3.6
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("V3.6 供给缺口：分桶边界 / 同一人不重复计数 / 空桶为 0 / 缺口符号")
class OperationsGapTest {

    @Mock
    private OperationsMapper mapper;

    @Mock
    private AdminService adminService;

    @Mock
    private MetricCalculatorRegistry metricCalculatorRegistry;

    private final OperationsProperties properties = new OperationsProperties();

    private OperationsServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new OperationsServiceImpl(mapper, adminService, properties, metricCalculatorRegistry);
    }

    /* ================================================================== */
    /* 1 · 桶的形状                                                        */
    /* ================================================================== */

    @Test
    @DisplayName("桶固定 168 个（7 天 × 24 小时），按周内第几天 → 小时升序")
    void shouldAlwaysReturn168Buckets() {
        stubNothing();

        OperationsGapVO vo = service.gap(new OperationsGapQuery());

        assertEquals(168, vo.getTotal(), "桶数必须是 168（7 × 24）");
        assertEquals(168, vo.getItems().size(), "items 长度必须与 total 一致");
        assertEquals(1, vo.getItems().get(0).getWeekday());
        assertEquals(0, vo.getItems().get(0).getHour());
        assertEquals(7, vo.getItems().get(167).getWeekday());
        assertEquals(23, vo.getItems().get(167).getHour());
    }

    @Test
    @DisplayName("周内第几天：1=周一 … 7=周日（周日是 7，不是 0）")
    void shouldUseIsoWeekdayNumbering() {
        // 2026-10-19 是周一，2026-10-18 是周日
        stubPending(Map.of("id", 1L, "visitTime", LocalDateTime.parse("2026-10-19T09:00:00")),
                Map.of("id", 2L, "visitTime", LocalDateTime.parse("2026-10-18T23:30:00")));

        OperationsGapVO vo = service.gap(new OperationsGapQuery());

        assertEquals(1, itemOf(vo, 1, 9).getPendingUnaccepted(), "周一应落在 weekday=1");
        assertEquals(1, itemOf(vo, 7, 23).getPendingUnaccepted(), "周日应落在 weekday=7");
        assertEquals(0, itemOf(vo, 7, 0).getPendingUnaccepted(), "周日凌晨不该有单");
    }

    @Test
    @DisplayName("小时边界：0 点与 23 点各有一个桶")
    void shouldCoverHourZeroAndTwentyThree() {
        stubPending(Map.of("id", 1L, "visitTime", LocalDateTime.parse("2026-10-19T00:15:00")),
                Map.of("id", 2L, "visitTime", LocalDateTime.parse("2026-10-19T23:45:00")));

        OperationsGapVO vo = service.gap(new OperationsGapQuery());

        assertEquals(1, itemOf(vo, 1, 0).getPendingUnaccepted());
        assertEquals(1, itemOf(vo, 1, 23).getPendingUnaccepted());
    }

    /* ================================================================== */
    /* 2 · 空桶 vs 缺行                                                   */
    /* ================================================================== */

    @Test
    @DisplayName("没有数据的时段也要有一行（计数为 0），不是缺行")
    void emptyBucketShouldBeZeroNotMissing() {
        stubPending(Map.of("id", 1L, "visitTime", LocalDateTime.parse("2026-10-19T09:00:00")));

        OperationsGapVO vo = service.gap(new OperationsGapQuery());

        OperationsGapVO.Item empty = itemOf(vo, 3, 15);
        assertNotNull(empty, "没有数据的时段也必须有一行");
        assertEquals(0, empty.getPendingUnaccepted());
        assertEquals(0, empty.getAvailableCompanions());
        assertEquals(0, empty.getGap());
    }

    /* ================================================================== */
    /* 3 · 供给按「可服务时段」分桶                                        */
    /* ================================================================== */

    @Test
    @DisplayName("同一陪诊员在同一桶里只计 1 次（多个时段重叠也不重复计）")
    void sameCompanionShouldBeCountedOncePerBucket() {
        // 301 号：周一 09:00-12:00 与周一 10:00-14:00 重叠
        stubSlots(Map.of("companionId", 301L, "weekday", 1, "startTime", "09:00:00", "endTime", "12:00:00"),
                Map.of("companionId", 301L, "weekday", 1, "startTime", "10:00:00", "endTime", "14:00:00"));
        stubProfiles(row("companionId", 301L, "availableTime", null));

        OperationsGapVO vo = service.gap(new OperationsGapQuery());

        // 9–13 点每个桶都应该是 1，而不是 2
        for (int hour = 9; hour <= 13; hour++) {
            assertEquals(1, itemOf(vo, 1, hour).getAvailableCompanions(),
                    "第 " + hour + " 点的供给数应该是 1（同一陪诊员只计一次）");
        }
        // 8 点与 14 点不覆盖（12:00–14:00 覆盖 12、13；整点 14:00 不在 14:00–12:00 区间内）
        assertEquals(0, itemOf(vo, 1, 8).getAvailableCompanions());
        assertEquals(0, itemOf(vo, 1, 14).getAvailableCompanions());
    }

    @Test
    @DisplayName("同一陪诊员在多个时段 → 每个桶各计 1 次（不重复、也不漏）")
    void sameCompanionShouldCountOnceInEachOfHisBuckets() {
        stubSlots(Map.of("companionId", 301L, "weekday", 1, "startTime", "09:00:00", "endTime", "10:00:00"),
                Map.of("companionId", 301L, "weekday", 2, "startTime", "14:00:00", "endTime", "16:00:00"));
        stubProfiles(row("companionId", 301L, "availableTime", null));

        OperationsGapVO vo = service.gap(new OperationsGapQuery());

        assertEquals(1, itemOf(vo, 1, 9).getAvailableCompanions());
        assertEquals(1, itemOf(vo, 2, 14).getAvailableCompanions());
        assertEquals(1, itemOf(vo, 2, 15).getAvailableCompanions());
        assertEquals(0, itemOf(vo, 1, 14).getAvailableCompanions());
    }

    @Test
    @DisplayName("结构化表没有该人时，回退解析存量自由文本（周一至周五 08:00-18:00）")
    void shouldFallBackToLegacyFreeTextSlots() {
        stubSlots();
        stubProfiles(Map.of("companionId", 302L, "availableTime", "周一至周五 08:00-18:00"));

        OperationsGapVO vo = service.gap(new OperationsGapQuery());

        // 08:00–18:00 覆盖 8–17 点，周一到周五
        assertEquals(1, itemOf(vo, 1, 8).getAvailableCompanions());
        assertEquals(1, itemOf(vo, 5, 17).getAvailableCompanions());
        assertEquals(0, itemOf(vo, 6, 8).getAvailableCompanions(), "周六不在「周一至周五」里");
        assertEquals(0, itemOf(vo, 1, 18).getAvailableCompanions(), "18:00 不在 08:00–18:00 区间内");
        assertEquals(0, vo.getCompanionsWithoutSlots(), "能解析出时段的陪诊员不算「无可用时段」");
    }

    @Test
    @DisplayName("结构化时段优先于自由文本（两者都有时用结构化的）")
    void structuredSlotsShouldWinOverLegacyText() {
        stubSlots(Map.of("companionId", 301L, "weekday", 1, "startTime", "09:00:00", "endTime", "10:00:00"));
        stubProfiles(Map.of("companionId", 301L, "availableTime", "周一至周五 08:00-18:00"));

        OperationsGapVO vo = service.gap(new OperationsGapQuery());

        assertEquals(1, itemOf(vo, 1, 9).getAvailableCompanions());
        assertEquals(0, itemOf(vo, 1, 8).getAvailableCompanions(),
                "有结构化时段时不该再用自由文本的 08:00");
    }

    @Test
    @DisplayName("解析不出时段的陪诊员不进任何桶，由 companionsWithoutSlots 如实披露")
    void companionsWithoutSlotsShouldBeDisclosed() {
        stubSlots(Map.of("companionId", 301L, "weekday", 1, "startTime", "09:00:00", "endTime", "10:00:00"));
        stubProfiles(Map.of("companionId", 301L, "availableTime", "随时可以"),
                row("companionId", 303L, "availableTime", null));

        OperationsGapVO vo = service.gap(new OperationsGapQuery());

        assertEquals(1, vo.getCompanionsWithoutSlots(), "解析不出时段的陪诊员要被如实计数");
        assertEquals(1, itemOf(vo, 1, 9).getAvailableCompanions());
        int totalSupply = vo.getItems().stream().mapToInt(OperationsGapVO.Item::getAvailableCompanions).sum();
        assertEquals(1, totalSupply, "不可解析的陪诊员不能混进任何桶");
    }

    /* ================================================================== */
    /* 4 · 缺口的符号                                                      */
    /* ================================================================== */

    @Test
    @DisplayName("gap = 待接未接单数 − 可服务陪诊员数（正数是缺口，负数是供给过剩）")
    void gapShouldBeDemandMinusSupply() {
        // 周六 14:00 有 3 单没人接，可服务的只有 1 人
        stubPending(Map.of("id", 1L, "visitTime", LocalDateTime.parse("2026-10-17T14:00:00")),
                Map.of("id", 2L, "visitTime", LocalDateTime.parse("2026-10-17T14:30:00")),
                Map.of("id", 3L, "visitTime", LocalDateTime.parse("2026-10-17T14:45:00")));
        stubSlots(Map.of("companionId", 301L, "weekday", 6, "startTime", "14:00:00", "endTime", "15:00:00"));
        stubProfiles(row("companionId", 301L, "availableTime", null));

        OperationsGapVO vo = service.gap(new OperationsGapQuery());

        OperationsGapVO.Item item = itemOf(vo, 6, 14);
        assertEquals(3, item.getPendingUnaccepted());
        assertEquals(1, item.getAvailableCompanions());
        assertEquals(2, item.getGap(), "3 − 1 = 2，> 0 即为缺口");
    }

    @Test
    @DisplayName("供给多于待接量时 gap 为负（不截断成 0）")
    void gapShouldBeNegativeWhenSupplyExceedsDemand() {
        stubPending(Map.of("id", 1L, "visitTime", LocalDateTime.parse("2026-10-17T14:00:00")));
        stubSlots(Map.of("companionId", 301L, "weekday", 6, "startTime", "14:00:00", "endTime", "15:00:00"),
                Map.of("companionId", 302L, "weekday", 6, "startTime", "14:00:00", "endTime", "15:00:00"));
        stubProfiles(row("companionId", 301L, "availableTime", null),
                row("companionId", 302L, "availableTime", null));

        OperationsGapVO vo = service.gap(new OperationsGapQuery());

        OperationsGapVO.Item item = itemOf(vo, 6, 14);
        assertEquals(1, item.getPendingUnaccepted());
        assertEquals(2, item.getAvailableCompanions());
        assertEquals(-1, item.getGap(), "供给多于需求时缺口是负数，不能被截断成 0");
    }

    /* ================================================================== */
    /* 5 · 区间与档位                                                      */
    /* ================================================================== */

    @Test
    @DisplayName("非法区间档位抛 BusinessException（400），且不查库")
    void shouldRejectInvalidRangeType() {
        OperationsGapQuery query = new OperationsGapQuery();
        query.setRange("LAST_99Y");

        BusinessException ex = assertThrows(BusinessException.class, () -> service.gap(query));

        assertEquals(400, ex.getCode());
    }

    @Test
    @DisplayName("CUSTOM 档位读 startDate / endDate，区间长度照常返回")
    void customRangeShouldBeHonoured() {
        stubNothing();
        OperationsGapQuery query = new OperationsGapQuery();
        query.setRange("CUSTOM");
        query.setStartDate("2026-09-01");
        query.setEndDate("2026-09-07");

        OperationsGapVO vo = service.gap(query);

        assertEquals("2026-09-01", vo.getPeriodStart());
        assertEquals("2026-09-07", vo.getPeriodEnd());
    }

    @Test
    @DisplayName("CUSTOM 档位下起止颠倒 → 9002")
    void customRangeShouldRejectReversedDates() {
        OperationsGapQuery query = new OperationsGapQuery();
        query.setRange("CUSTOM");
        query.setStartDate("2026-09-07");
        query.setEndDate("2026-09-01");

        BusinessException ex = assertThrows(BusinessException.class, () -> service.gap(query));

        assertEquals(9002, ex.getCode());
    }

    /* ================================================================== */
    /* 工具                                                                */
    /* ================================================================== */

    /** 取某个桶；找不到直接失败（缺行是 bug，不是正常情况） */
    private static OperationsGapVO.Item itemOf(OperationsGapVO vo, int weekday, int hour) {
        for (OperationsGapVO.Item item : vo.getItems()) {
            if (item.getWeekday() == weekday && item.getHour() == hour) {
                return item;
            }
        }
        throw new AssertionError("没有返回这个桶：" + weekday + "-" + hour);
    }

    private void stubNothing() {
        when(mapper.selectPendingOrderVisitTimes(any(), any())).thenReturn(List.of());
        when(mapper.selectCompanionServiceSlots()).thenReturn(List.of());
        when(mapper.selectCompanionAvailableTimes()).thenReturn(List.of());
    }

    @SafeVarargs
    private void stubPending(Map<String, Object>... rows) {
        when(mapper.selectPendingOrderVisitTimes(any(), any())).thenReturn(List.of(rows));
    }

    @SafeVarargs
    private void stubSlots(Map<String, Object>... rows) {
        when(mapper.selectCompanionServiceSlots()).thenReturn(List.of(rows));
    }

    @SafeVarargs
    private void stubProfiles(Map<String, Object>... rows) {
        when(mapper.selectCompanionAvailableTimes()).thenReturn(List.of(rows));
    }

    /** 造一行查询结果（允许 null 值 —— {@code Map.of} 不允许） */
    private static Map<String, Object> row(Object... keyValues) {
        Map<String, Object> map = new HashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            map.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        }
        return map;
    }

    /** 供「空数据」用例断言用：确保返回的 VO 不含 null 字段 */
    @Test
    @DisplayName("空数据时返回 0 与空桶列表，不返回 null、不抛异常")
    void shouldReturnZerosOnEmptyData() {
        stubNothing();

        OperationsGapVO vo = service.gap(new OperationsGapQuery());

        assertNotNull(vo);
        assertEquals(0, vo.getCompanionsWithoutSlots());
        assertEquals(168, vo.getTotal());
        assertTrue(vo.getItems().stream().allMatch(item -> item.getGap() == 0));
    }
}

package org.company.nianglin.service.support.matching;

import org.company.nianglin.config.MatchingProperties;
import org.company.nianglin.entity.CompanionOrder;
import org.company.nianglin.mapper.MatchingMapper;
import org.company.nianglin.service.impl.MatchingServiceImpl;
import org.company.nianglin.vo.CompanionMatchVO;
import org.company.nianglin.vo.OrderRecommendVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

/**
 * V3.4 智能调度编排层的单测（T5 · FR-Service）。
 *
 * <h3>这个测试在防什么</h3>
 *
 * <p>编排层最容易失守的不是「算错分」，而是<b>过滤链的语义漂移</b>：
 * 跨市的人混进推荐、没填资料的人被当成「不匹配」而淘汰、
 * 「没有理由的分数」悄悄出现在家属端。</p>
 *
 * <p>所以这里锁死三件事：</p>
 * <ol>
 *   <li><b>过滤链顺序与短路</b>：五步逐级短路，每一步都能单独触发淘汰；</li>
 *   <li><b>「缺数据」与「不匹配」分开</b>：没区域码 / 没时段 → 放行 + 降级，
 *       跨市 / 时段外 / 档期冲突 → 淘汰；</li>
 *   <li><b>可解释性</b>：每条候选至少一条理由，降级说明只进 {@code notes}。</li>
 * </ol>
 *
 * <h3>断言写死的值</h3>
 *
 * <p>候选 301 的五维分是算得出来的：区域 1.0 × 0.30 + 时间 1.0 × 0.25 +
 * 距离 1.0 × 0.20 + 能力 0.6 × 0.15 + 评分 0.96 × 0.10 = <b>0.936 → 94 分</b>。
 * 把它写死，任何一处权重、归一化或舍入的改动都会立刻对不上。</p>
 *
 * @author 银龄伴诊团队
 * @since V3.4
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("V3.4 智能调度编排：过滤链逐级短路 + 每条候选都能说出理由")
class MatchingServiceTest {

    /** 订单坐标（海口美兰） */
    private static final BigDecimal LON = new BigDecimal("110.311200");
    private static final BigDecimal LAT = new BigDecimal("20.021500");

    /** 就诊时间：2026-09-21 是周一 */
    private static final LocalDateTime VISIT_TIME = LocalDateTime.of(2026, 9, 21, 10, 0);

    @Mock
    private MatchingMapper matchingMapper;

    private MatchingServiceImpl service;

    @BeforeEach
    void setUp() {
        MatchingProperties properties = new MatchingProperties();
        service = new MatchingServiceImpl(matchingMapper, properties);
    }

    /* ================================================================== */
    /* 1. 过滤链：五步逐级短路                                            */
    /* ================================================================== */

    @Test
    @DisplayName("工作状态不是 AVAILABLE → 淘汰")
    void notAvailableCompanionShouldBeFilteredOut() {
        stubCandidates(row(301L, "李建军")
                .workStatus("REST")
                .regionCode("HAIKOU_MEILAN")
                .availableTime("周一至周五 08:00-18:00")
                .score(new BigDecimal("4.80")));
        OrderRecommendVO result = service.recommendCompanions(order(), 10);

        assertEquals(0, result.getTotal(), "休息中的陪诊员不该进候选");
        assertTrue(result.getCandidates().isEmpty());
    }

    @Test
    @DisplayName("资质不是 APPROVED → 淘汰")
    void notApprovedCompanionShouldBeFilteredOut() {
        stubCandidates(row(301L, "李建军")
                .auditStatus("PENDING")
                .regionCode("HAIKOU_MEILAN")
                .availableTime("周一至周五 08:00-18:00")
                .score(new BigDecimal("4.80")));
        OrderRecommendVO result = service.recommendCompanions(order(), 10);

        assertEquals(0, result.getTotal(), "资质未通过的陪诊员不该进候选");
        assertTrue(result.getCandidates().isEmpty());
    }

    @Test
    @DisplayName("区域跨市 → 淘汰；同市不同区 → 保留")
    void crossCityCompanionShouldBeFilteredOutButSameCityKept() {
        stubCandidates(
                row(301L, "李建军").regionCode("SANYA_JIYANG")
                        .availableTime("周一至周五 08:00-18:00").score(new BigDecimal("4.80")),
                row(302L, "王秀兰").regionCode("HAIKOU_XIUYING")
                        .availableTime("周一至周五 08:00-18:00").score(new BigDecimal("4.00")));
        OrderRecommendVO result = service.recommendCompanions(order(), 10);

        assertEquals(1, result.getTotal(), "跨市的要被淘汰，同市的要留下");
        assertEquals(302L, result.getCandidates().get(0).getCompanionId().longValue());
    }

    @Test
    @DisplayName("登记了可服务时段但订单不在时段内 → 淘汰")
    void companionOutsideSlotsShouldBeFilteredOut() {
        stubCandidates(row(301L, "李建军")
                .regionCode("HAIKOU_MEILAN")
                .availableTime("周二至周六 08:00-18:00")
                .score(new BigDecimal("4.80")));
        OrderRecommendVO result = service.recommendCompanions(order(), 10);

        assertEquals(0, result.getTotal(), "周一上班、周二才上班的人不该接周一的单");
    }

    @Test
    @DisplayName("档期冲突（在手订单与就诊时间重叠）→ 淘汰")
    void conflictingCompanionShouldBeFilteredOut() {
        stubCandidates(row(301L, "李建军")
                .regionCode("HAIKOU_MEILAN")
                .availableTime("周一至周五 08:00-18:00")
                .score(new BigDecimal("4.80")));
        // 订单夹具：海口美兰 / 周一 10:00 / 心血管内科（见 order()）
        given(matchingMapper.selectInFlightOrders(any())).willReturn(List.of(
                Map.of("companionId", 301L, "orderId", 1999L, "visitTime", VISIT_TIME)));

        OrderRecommendVO result = service.recommendCompanions(order(), 10);

        assertEquals(0, result.getTotal(), "同一时段已有在手订单的陪诊员不该进候选");
    }

    @Test
    @DisplayName("没有登记时段的候选 → 保留（降级），不是淘汰")
    void companionWithoutSlotsShouldBeKeptButDegraded() {
        stubCandidates(row(301L, "李建军")
                .regionCode("HAIKOU_MEILAN")
                .availableTime("时间待定")
                .score(new BigDecimal("4.80")));
        OrderRecommendVO result = service.recommendCompanions(order(), 10);

        assertEquals(1, result.getTotal(), "没登记时段是资料缺失，不能当成不匹配而淘汰");
        CompanionMatchVO candidate = result.getCandidates().get(0);
        assertEquals(0, candidate.getTimeScore(), "时间维度降级记 0");
        assertTrue(candidate.getNotes().stream().anyMatch(n -> n.contains("未登记可服务时段")),
                "降级说明要写明未登记时段：" + candidate.getNotes());
        assertFalse(candidate.getReasons().stream().anyMatch(r -> r.contains("未登记可服务时段")),
                "降级说明不得混进命中理由：" + candidate.getReasons());
    }

    /* ================================================================== */
    /* 2. 可解释性                                                          */
    /* ================================================================== */

    @Test
    @DisplayName("五维分与总分按权重算死：区域/时间/距离满分 + 能力 12 单 + 评分 4.8 → 94 分")
    void dimensionScoresShouldBeWeightedAndPinned() {
        stubCandidates(row(301L, "李建军")
                .regionCode("HAIKOU_MEILAN")
                .serviceArea("海口市美兰区")
                .availableTime("周一至周五 08:00-18:00")
                .longitude(LON)
                .latitude(LAT)
                .score(new BigDecimal("4.80")));
        // 订单夹具：海口美兰 / 周一 10:00 / 心血管内科（见 order()）
        given(matchingMapper.selectDepartmentOrderCounts(any())).willReturn(List.of(
                Map.of("companionId", 301L, "department", "心血管内科", "cnt", 12)));

        OrderRecommendVO result = service.recommendCompanions(order(), 10);

        CompanionMatchVO candidate = result.getCandidates().get(0);
        // 0.30×100 + 0.25×100 + 0.20×100 + 0.15×60 + 0.10×96 = 93.6 → 94
        assertEquals(100, candidate.getRegionScore());
        assertEquals(100, candidate.getTimeScore());
        assertEquals(100, candidate.getDistanceScore());
        assertEquals(60, candidate.getSkillScore());
        assertEquals(96, candidate.getRatingScore());
        assertEquals(94, candidate.getTotalScore(), "加权总分 = 93.6，四舍五入到 94");
    }

    @Test
    @DisplayName("每条候选至少有一条理由（不允许出现「没有理由的分数」）")
    void everyCandidateShouldHaveAtLeastOneReason() {
        stubCandidates(row(301L, "李建军")
                .regionCode("HAIKOU_MEILAN")
                .availableTime("周一至周五 08:00-18:00")
                .score(new BigDecimal("4.80")));
        OrderRecommendVO result = service.recommendCompanions(order(), 10);

        assertFalse(result.getCandidates().isEmpty(), "前提失效：没有候选");
        for (CompanionMatchVO candidate : result.getCandidates()) {
            assertNotNull(candidate.getReasons(), "理由列表不能为 null");
            assertFalse(candidate.getReasons().isEmpty(),
                    "陪诊员 " + candidate.getCompanionId() + " 的分数没有任何理由");
        }
    }

    @Test
    @DisplayName("降级维度的说明只出现在 notes 里，不出现在 reasons 里")
    void degradedNotesShouldNotLeakIntoReasons() {
        stubCandidates(row(301L, "李建军")
                .regionCode("HAIKOU_MEILAN")
                .availableTime("周一至周五 08:00-18:00")
                .score(new BigDecimal("4.80")));
        // 订单夹具：海口美兰 / 周一 10:00 / 心血管内科（见 order()）
        // 陪诊员没有坐标 → 距离维度降级记 0

        OrderRecommendVO result = service.recommendCompanions(order(), 10);

        CompanionMatchVO candidate = result.getCandidates().get(0);
        assertEquals(0, candidate.getDistanceScore());
        assertTrue(candidate.getNotes().stream().anyMatch(n -> n.contains("未填写坐标")),
                "降级说明要写明未填写坐标：" + candidate.getNotes());
        assertFalse(candidate.getReasons().stream().anyMatch(r -> r.contains("未填写坐标")),
                "降级说明不得混进命中理由：" + candidate.getReasons());
    }

    @Test
    @DisplayName("总分与各维度分都落在 0-100")
    void scoresShouldStayInHundredRange() {
        stubCandidates(row(301L, "李建军")
                .regionCode("HAIKOU_MEILAN")
                .availableTime("周一至周五 08:00-18:00")
                .score(new BigDecimal("4.80")));
        OrderRecommendVO result = service.recommendCompanions(order(), 10);

        for (CompanionMatchVO candidate : result.getCandidates()) {
            assertTrue(candidate.getTotalScore() >= 0 && candidate.getTotalScore() <= 100,
                    "总分越界：" + candidate.getTotalScore());
            assertTrue(candidate.getRegionScore() >= 0 && candidate.getRegionScore() <= 100);
            assertTrue(candidate.getTimeScore() >= 0 && candidate.getTimeScore() <= 100);
            assertTrue(candidate.getDistanceScore() >= 0 && candidate.getDistanceScore() <= 100);
            assertTrue(candidate.getSkillScore() >= 0 && candidate.getSkillScore() <= 100);
            assertTrue(candidate.getRatingScore() >= 0 && candidate.getRatingScore() <= 100);
        }
    }

    @Test
    @DisplayName("候选姓名脱敏：李建军 → 李*军")
    void companionNameShouldBeMasked() {
        stubCandidates(row(301L, "李建军")
                .regionCode("HAIKOU_MEILAN")
                .availableTime("周一至周五 08:00-18:00")
                .score(new BigDecimal("4.80")));
        OrderRecommendVO result = service.recommendCompanions(order(), 10);

        assertEquals("李*军", result.getCandidates().get(0).getCompanionName(),
                "家属端不允许看到陪诊员全名");
    }

    /* ================================================================== */
    /* 3. total 与 Top N                                                     */
    /* ================================================================== */

    @Test
    @DisplayName("total 是通过过滤链的候选总数，不只是返回的 Top N")
    void totalShouldCountAllPassedCandidates() {
        stubCandidates(
                row(301L, "李建军").regionCode("HAIKOU_MEILAN")
                        .availableTime("周一至周五 08:00-18:00").score(new BigDecimal("4.80")),
                row(302L, "王秀兰").regionCode("HAIKOU_MEILAN")
                        .availableTime("周一至周五 08:00-18:00").score(new BigDecimal("4.00")),
                row(303L, "陈志强").regionCode("HAIKOU_MEILAN")
                        .availableTime("周一至周五 08:00-18:00").score(new BigDecimal("4.18")));
        OrderRecommendVO result = service.recommendCompanions(order(), 1);

        assertEquals(3, result.getTotal(), "total 是候选总数");
        assertEquals(1, result.getCandidates().size(), "但只返回 Top 1");
    }

    /* ================================================================== */
    /* 4. 排序                                                              */
    /* ================================================================== */

    @Test
    @DisplayName("排序：总分降序（距离维度把候选拉开差距）")
    void shouldSortByTotalScoreDescending() {
        stubCandidates(
                // 有坐标且与订单同点 → 距离 1.0 → 总分 94
                row(301L, "李建军").regionCode("HAIKOU_MEILAN")
                        .availableTime("周一至周五 08:00-18:00")
                        .longitude(LON).latitude(LAT).score(new BigDecimal("4.80")),
                // 无坐标 → 距离降级 0 → 总分 63
                row(302L, "王秀兰").regionCode("HAIKOU_MEILAN")
                        .availableTime("周一至周五 08:00-18:00").score(new BigDecimal("4.00")));
        OrderRecommendVO result = service.recommendCompanions(order(), 10);

        assertEquals(2, result.getTotal());
        assertEquals(301L, result.getCandidates().get(0).getCompanionId().longValue(),
                "同点的人分数更高，应排前面");
        // 0.30×100 + 0.25×100 + 0.20×100 + 0.15×0 + 0.10×96 = 84.6 → 85
        assertEquals(85, result.getCandidates().get(0).getTotalScore());
        // 0.30×100 + 0.25×100 + 0.20×0 + 0.15×0 + 0.10×80 = 63
        assertEquals(63, result.getCandidates().get(1).getTotalScore());
    }

    @Test
    @DisplayName("同分时按陪诊员 ID 升序（顺序必须稳定）")
    void sameScoreShouldFallBackToCompanionIdAscending() {
        stubCandidates(
                row(304L, "王秀兰").regionCode("HAIKOU_MEILAN")
                        .availableTime("周一至周五 08:00-18:00").score(new BigDecimal("4.00")),
                row(303L, "陈志强").regionCode("HAIKOU_MEILAN")
                        .availableTime("周一至周五 08:00-18:00").score(new BigDecimal("4.00")));
        OrderRecommendVO result = service.recommendCompanions(order(), 10);

        assertEquals(2, result.getTotal());
        assertEquals(303L, result.getCandidates().get(0).getCompanionId().longValue(), "同分时 ID 小的排前面");
        assertEquals(304L, result.getCandidates().get(1).getCompanionId().longValue());
        assertEquals(result.getCandidates().get(0).getTotalScore(),
                result.getCandidates().get(1).getTotalScore(), "同分才走 ID 兜底");
    }

    /* ================================================================== */
    /* 夹具                                                                */
    /* ================================================================== */

    /** 把候选集与配套的时段 / 单数 / 在手订单一次性铺好 */
    private void stubCandidates(RowBuilder... rows) {
        List<Map<String, Object>> candidates = new ArrayList<>();
        for (RowBuilder row : rows) {
            candidates.add(row.build());
        }
        given(matchingMapper.selectCandidates()).willReturn(candidates);
        // 结构化时段表为空 → 走自由文本回退路径
        given(matchingMapper.selectSlots(any())).willReturn(List.of());
        given(matchingMapper.selectDepartmentOrderCounts(any())).willReturn(List.of());
        given(matchingMapper.selectInFlightOrders(any())).willReturn(List.of());
    }

    /** 订单夹具：区域码 / 地址 / 就诊时间 / 坐标 / 科室全部固定，便于逐条算清分值 */
    private CompanionOrder order() {
        CompanionOrder order = new CompanionOrder();
        order.setId(2001L);
        order.setServiceRegionCode("HAIKOU_MEILAN");
        order.setAddress("海南省海口市美兰区人民大道43号");
        order.setVisitTime(VISIT_TIME);
        order.setLongitude(LON);
        order.setLatitude(LAT);
        order.setDepartment("心血管内科");
        return order;
    }

    private static RowBuilder row(Long userId, String realName) {
        return new RowBuilder(userId, realName);
    }

    /** 候选行构造器（对应 {@code MatchingMapper#selectCandidates} 的列） */
    private static final class RowBuilder {
        private final Map<String, Object> row = new LinkedHashMap<>();

        RowBuilder(Long userId, String realName) {
            row.put("userId", userId);
            row.put("realName", realName);
            row.put("auditStatus", "APPROVED");
            row.put("workStatus", "AVAILABLE");
            row.put("regionCode", null);
            row.put("serviceArea", null);
            row.put("availableTime", null);
            row.put("longitude", null);
            row.put("latitude", null);
            row.put("score", null);
        }

        RowBuilder auditStatus(String auditStatus) {
            row.put("auditStatus", auditStatus);
            return this;
        }

        RowBuilder workStatus(String workStatus) {
            row.put("workStatus", workStatus);
            return this;
        }

        RowBuilder regionCode(String regionCode) {
            row.put("regionCode", regionCode);
            return this;
        }

        RowBuilder serviceArea(String serviceArea) {
            row.put("serviceArea", serviceArea);
            return this;
        }

        RowBuilder availableTime(String availableTime) {
            row.put("availableTime", availableTime);
            return this;
        }

        RowBuilder longitude(BigDecimal longitude) {
            row.put("longitude", longitude);
            return this;
        }

        RowBuilder latitude(BigDecimal latitude) {
            row.put("latitude", latitude);
            return this;
        }

        RowBuilder score(BigDecimal score) {
            row.put("score", score);
            return this;
        }

        Map<String, Object> build() {
            return new HashMap<>(row);
        }
    }
}

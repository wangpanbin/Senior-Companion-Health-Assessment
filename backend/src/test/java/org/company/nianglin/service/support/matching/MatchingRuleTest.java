package org.company.nianglin.service.support.matching;

import org.company.nianglin.util.GeoUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V3.4 匹配规则的<b>纯函数</b>单测（T4 · FR-Rule）。
 *
 * <h3>为什么规则测试不能有 Spring 上下文</h3>
 *
 * <p>规则只吃快照、只吐结果，不碰数据库、不碰时钟、不碰配置中心。
 * 所以这里全部是普通 {@code new}：毫秒级地反复测边界值 ——
 * 而「每个阈值的边界」正是这个引擎最需要被锁住的东西。</p>
 *
 * <h3>反向验证长什么样</h3>
 *
 * <p>本类的反向用例不是「把代码改坏再断言变红」那种手工流程，
 * 而是<b>断言写死了正确的那个值</b>：区域码相等就断言 {@code 1.0}、
 * 同市不同区就断言 {@code 0.5}、跨市就断言 {@code 0.0}；
 * 距离无坐标就断言 {@code 0.0} 且 {@code note} 里出现「未填写坐标」。</p>
 *
 * <p>最关键的一条是<b>距离维度的「无坐标 → 记 0 且不推断」</b>：
 * 如果哪天有人把 {@code null} 坐标当成 {@code (0,0)} 去算 Haversine，
 * 会得到「离几内亚湾 2000 公里」的荒谬分数，本类的
 * {@link #distance} 用例会立刻红。</p>
 *
 * @author 银龄伴诊团队
 * @since V3.4
 */
@DisplayName("V3.4 匹配规则：五条规则的得分边界 + 降级语义")
class MatchingRuleTest {

    /** 订单坐标（海口美兰，与种子一致） */
    private static final BigDecimal LON_HAIKOU = new BigDecimal("110.311200");
    private static final BigDecimal LAT_HAIKOU = new BigDecimal("20.021500");

    /** 另一个点（海口龙华一带，约 1 公里量级） */
    private static final BigDecimal LON_HAIKOU_2 = new BigDecimal("110.320000");
    private static final BigDecimal LAT_HAIKOU_2 = new BigDecimal("20.030000");

    /** 三亚吉阳，用于「跨城很远」的判定 */
    private static final BigDecimal LON_SANYA = new BigDecimal("109.508300");
    private static final BigDecimal LAT_SANYA = new BigDecimal("18.247900");

    /** 2026-09-21 是周一（周内第 1 天） */
    private static final LocalDateTime MONDAY_MORNING = LocalDateTime.of(2026, 9, 21, 10, 0);
    private static final LocalDateTime MONDAY_NIGHT = LocalDateTime.of(2026, 9, 21, 20, 0);
    private static final LocalDateTime TUESDAY_MORNING = LocalDateTime.of(2026, 9, 22, 10, 0);
    private static final LocalDateTime MONDAY_LATE_NIGHT = LocalDateTime.of(2026, 9, 21, 23, 30);

    /* ================================================================== */
    /* 1. 区域                                                              */
    /* ================================================================== */

    @Nested
    @DisplayName("区域维度")
    class Region {

        @Test
        @DisplayName("区域码相等 → 1.0，且不是降级")
        void sameRegionCodeShouldScoreOne() {
            DimensionResult result = new RegionMatchRule().evaluate(
                    candidate().regionCode("HAIKOU_MEILAN").build(),
                    target().regionCode("HAIKOU_MEILAN").build());

            assertEquals(1.0D, result.score(), 1e-9D, "区域码相等必须满分");
            assertFalse(result.isDegraded(), "区域码相等不是数据缺失，不该降级");
            assertEquals("同服务区域", result.reason());
        }

        @Test
        @DisplayName("同市不同区 → 0.5（海口美兰 vs 海口秀英）")
        void sameCityDifferentDistrictShouldScoreHalf() {
            DimensionResult result = new RegionMatchRule().evaluate(
                    candidate().regionCode("HAIKOU_MEILAN").build(),
                    target().regionCode("HAIKOU_XIUYING").build());

            assertEquals(0.5D, result.score(), 1e-9D, "同市不同区必须记半分");
            assertFalse(result.isDegraded());
            assertEquals("同一城市不同区域", result.reason());
        }

        @Test
        @DisplayName("跨市 → 0.0（海口美兰 vs 三亚吉阳）")
        void crossCityShouldScoreZero() {
            DimensionResult result = new RegionMatchRule().evaluate(
                    candidate().regionCode("HAIKOU_MEILAN").build(),
                    target().regionCode("SANYA_JIYANG").build());

            assertEquals(0.0D, result.score(), 1e-9D, "跨市必须记 0");
            assertFalse(result.isDegraded(), "跨市是「不匹配」，不是「算不出来」");
            assertEquals("跨城市", result.reason());
        }

        @Test
        @DisplayName("区域码缺失时回退自由文本：服务区域出现在订单地址里 → 命中，但标注降级")
        void freeTextFallbackShouldHitAndDegrade() {
            DimensionResult result = new RegionMatchRule().evaluate(
                    candidate().regionCode(null).serviceArea("美兰区").build(),
                    target().regionCode(null).address("海南省海口市美兰区人民大道43号").build());

            assertEquals(1.0D, result.score(), 1e-9D, "自由文本命中要按满分算");
            assertTrue(result.isDegraded(), "走了自由文本路径就是降级，必须标注");
            assertNotNull(result.note());
            assertTrue(result.note().contains("未登记区域码"), "降级说明要写明原因：" + result.note());
        }

        @Test
        @DisplayName("双方都没有可用信息 → 记 0，但必须给出降级说明（不许出现没有理由的 0）")
        void noRegionInfoShouldScoreZeroWithNote() {
            DimensionResult result = new RegionMatchRule().evaluate(
                    candidate().regionCode(null).serviceArea(null).build(),
                    target().regionCode(null).address(null).build());

            assertEquals(0.0D, result.score(), 1e-9D);
            assertTrue(result.isDegraded(), "缺数据必须降级");
            assertNotNull(result.note(), "没有理由的 0 不允许出现");
            assertTrue(result.note().contains("区域维度记 0"), "降级说明要写明该维度记 0：" + result.note());
        }

        @Test
        @DisplayName("有区域码但订单没有 → 同样降级，且不得凭空推断成命中")
        void targetWithoutRegionCodeShouldDegradeToZero() {
            DimensionResult result = new RegionMatchRule().evaluate(
                    candidate().regionCode("HAIKOU_MEILAN").serviceArea("海口市美兰区").build(),
                    target().regionCode(null).address("海南省海口市龙华区龙华路31号").build());

            assertEquals(0.0D, result.score(), 1e-9D, "订单无区域码且自由文本未命中时不得推断");
            assertTrue(result.isDegraded());
        }

        @Test
        @DisplayName("cityOf 取城市前缀：HAIKOU_MEILAN → HAIKOU")
        void cityOfShouldTakeCityPrefix() {
            assertEquals("HAIKOU", RegionMatchRule.cityOf("HAIKOU_MEILAN"));
            assertEquals("SANYA", RegionMatchRule.cityOf("SANYA_JIYANG"));
            assertEquals("DANZHOU", RegionMatchRule.cityOf("DANZHOU_NADA"),
                    "儋州那大镇的城市前缀是 DANZHOU");
            assertEquals("HAIKOU", RegionMatchRule.cityOf("HAIKOU"),
                    "没有下划线时原样返回，不猜城市");
        }
    }

    /* ================================================================== */
    /* 2. 时间                                                              */
    /* ================================================================== */

    @Nested
    @DisplayName("时间维度")
    class Time {

        @Test
        @DisplayName("就诊时间落在可服务时段内 → 1.0")
        void insideSlotShouldScoreOne() {
            DimensionResult result = new TimeMatchRule().evaluate(
                    candidate().slot(1, LocalTime.of(8, 0), LocalTime.of(18, 0)).build(),
                    target().visitTime(MONDAY_MORNING).build());

            assertEquals(1.0D, result.score(), 1e-9D);
            assertFalse(result.isDegraded());
            assertEquals("就诊时间在可服务时段内", result.reason());
        }

        @Test
        @DisplayName("同一天但时段外 → 0.5（那天他上班，只是这个钟点不行）")
        void sameDayOutsideSlotShouldScoreHalf() {
            DimensionResult result = new TimeMatchRule().evaluate(
                    candidate().slot(1, LocalTime.of(8, 0), LocalTime.of(18, 0)).build(),
                    target().visitTime(MONDAY_NIGHT).build());

            assertEquals(0.5D, result.score(), 1e-9D, "同一天时段外必须记半分");
            assertFalse(result.isDegraded());
            assertEquals("当天可服务，但就诊时刻不在时段内", result.reason());
        }

        @Test
        @DisplayName("不同天 → 0.0")
        void differentDayShouldScoreZero() {
            DimensionResult result = new TimeMatchRule().evaluate(
                    candidate().slot(1, LocalTime.of(8, 0), LocalTime.of(18, 0)).build(),
                    target().visitTime(TUESDAY_MORNING).build());

            assertEquals(0.0D, result.score(), 1e-9D, "不在可服务日必须记 0");
            assertFalse(result.isDegraded());
            assertEquals("就诊当天不在可服务日", result.reason());
        }

        @Test
        @DisplayName("候选没有登记时段 → 记 0 且降级（缺数据，不是「那天不上班」）")
        void noSlotsShouldDegradeToZero() {
            DimensionResult result = new TimeMatchRule().evaluate(
                    candidate().build(),
                    target().visitTime(MONDAY_MORNING).build());

            assertEquals(0.0D, result.score(), 1e-9D);
            assertTrue(result.isDegraded(), "没有时段是数据缺失，必须降级");
            assertTrue(result.note().contains("未登记可服务时段"), "降级说明要写明原因：" + result.note());
        }

        @Test
        @DisplayName("跨零点的时段 22:00-06:00，就诊 23:30 → 命中 1.0")
        void overnightSlotShouldMatchLateNight() {
            DimensionResult result = new TimeMatchRule().evaluate(
                    candidate().slot(1, LocalTime.of(22, 0), LocalTime.of(6, 0)).build(),
                    target().visitTime(MONDAY_LATE_NIGHT).build());

            assertEquals(1.0D, result.score(), 1e-9D, "跨零点时段必须能命中夜里");
            assertFalse(result.isDegraded());
        }

        @Test
        @DisplayName("订单没有就诊时间 → 记 0 且降级")
        void noVisitTimeShouldDegradeToZero() {
            DimensionResult result = new TimeMatchRule().evaluate(
                    candidate().slot(1, LocalTime.of(8, 0), LocalTime.of(18, 0)).build(),
                    target().visitTime(null).build());

            assertEquals(0.0D, result.score(), 1e-9D);
            assertTrue(result.isDegraded());
        }
    }

    /* ================================================================== */
    /* 3. 距离（本版最重要的一条）                                          */
    /* ================================================================== */

    @Nested
    @DisplayName("距离维度")
    class Distance {

        @Test
        @DisplayName("候选没有坐标 → 记 0 且写明「未填写坐标」（绝不按 (0,0) 算距离）")
        void candidateWithoutCoordinatesShouldDegradeToZero() {
            DimensionResult result = new DistanceMatchRule(30_000).evaluate(
                    candidate().longitude(null).latitude(null).build(),
                    target().longitude(LON_HAIKOU).latitude(LAT_HAIKOU).build());

            assertEquals(0.0D, result.score(), 1e-9D,
                    "无坐标必须记 0；按 (0,0) 算会得到几内亚湾级别的假距离");
            assertTrue(result.isDegraded());
            assertTrue(result.note().contains("未填写坐标"), "降级说明必须写明未填写坐标：" + result.note());
            assertTrue(result.reason().contains("未填写坐标"), "命中理由也要说清楚：" + result.reason());
        }

        @Test
        @DisplayName("订单没有坐标 → 同样降级记 0")
        void targetWithoutCoordinatesShouldDegradeToZero() {
            DimensionResult result = new DistanceMatchRule(30_000).evaluate(
                    candidate().longitude(LON_HAIKOU).latitude(LAT_HAIKOU).build(),
                    target().longitude(null).latitude(null).build());

            assertEquals(0.0D, result.score(), 1e-9D);
            assertTrue(result.isDegraded());
            assertTrue(result.note().contains("订单无坐标"), "降级说明要区分「订单无坐标」：" + result.note());
        }

        @Test
        @DisplayName("同一个点 → 1.0")
        void samePointShouldScoreOne() {
            DimensionResult result = new DistanceMatchRule(30_000).evaluate(
                    candidate().longitude(LON_HAIKOU).latitude(LAT_HAIKOU).build(),
                    target().longitude(LON_HAIKOU).latitude(LAT_HAIKOU).build());

            assertEquals(1.0D, result.score(), 1e-9D, "距离为 0 必须满分");
            assertFalse(result.isDegraded());
        }

        @Test
        @DisplayName("超出满量程 → 0.0，且不是降级（是真的远，不是算不出来）")
        void beyondFullScaleShouldScoreZero() {
            DimensionResult result = new DistanceMatchRule(30_000).evaluate(
                    candidate().longitude(LON_HAIKOU).latitude(LAT_HAIKOU).build(),
                    target().longitude(LON_SANYA).latitude(LAT_SANYA).build());

            assertEquals(0.0D, result.score(), 1e-9D, "超出满量程必须记 0");
            assertFalse(result.isDegraded(), "超出半径是「不匹配」，不该降级");
            assertEquals("超出服务半径", result.reason());
        }

        @Test
        @DisplayName("正好在满量程的一半 → 0.5")
        void halfOfFullScaleShouldScoreHalf() {
            int meters = GeoUtil.distanceMeters(LON_HAIKOU, LAT_HAIKOU, LON_HAIKOU_2, LAT_HAIKOU_2);
            assertTrue(meters > 0, "前提失效：两个测试点重合");

            DimensionResult result = new DistanceMatchRule(meters * 2).evaluate(
                    candidate().longitude(LON_HAIKOU).latitude(LAT_HAIKOU).build(),
                    target().longitude(LON_HAIKOU_2).latitude(LAT_HAIKOU_2).build());

            assertEquals(0.5D, result.score(), 1e-9D, "半程必须记半分");
            assertFalse(result.isDegraded());
        }

        @Test
        @DisplayName("满量程 = 1 米而实际相距约 1 公里 → 0.0")
        void beyondTinyFullScaleShouldScoreZero() {
            DimensionResult result = new DistanceMatchRule(1).evaluate(
                    candidate().longitude(LON_HAIKOU).latitude(LAT_HAIKOU).build(),
                    target().longitude(LON_HAIKOU_2).latitude(LAT_HAIKOU_2).build());

            assertEquals(0.0D, result.score(), 1e-9D);
            assertEquals("超出服务半径", result.reason());
        }
    }

    /* ================================================================== */
    /* 4. 服务能力                                                          */
    /* ================================================================== */

    @Nested
    @DisplayName("服务能力维度")
    class Skill {

        @Test
        @DisplayName("该科室一单没做过 → 0.0")
        void zeroCountShouldScoreZero() {
            DimensionResult result = new SkillMatchRule(20).evaluate(
                    candidate().departmentCounts(Map.of("心血管内科", 0)).build(),
                    target().department("心血管内科").build());

            assertEquals(0.0D, result.score(), 1e-9D);
            assertFalse(result.isDegraded(), "「没做过」是事实，不是数据缺失");
            assertEquals("未服务过该科室", result.reason());
        }

        @Test
        @DisplayName("做过正好满量程单数 → 1.0")
        void countEqualsFullScaleShouldScoreOne() {
            DimensionResult result = new SkillMatchRule(20).evaluate(
                    candidate().departmentCounts(Map.of("心血管内科", 20)).build(),
                    target().department("心血管内科").build());

            assertEquals(1.0D, result.score(), 1e-9D, "达到满量程必须满分");
            assertEquals("服务过该科室 20 单", result.reason());
        }

        @Test
        @DisplayName("超过满量程 → 仍然是 1.0（上限截断，不允许 > 1）")
        void countBeyondFullScaleShouldBeCappedAtOne() {
            DimensionResult result = new SkillMatchRule(20).evaluate(
                    candidate().departmentCounts(Map.of("心血管内科", 35)).build(),
                    target().department("心血管内科").build());

            assertEquals(1.0D, result.score(), 1e-9D, "超过满量程必须被截断在 1.0");
            assertTrue(result.score() <= 1.0D, "维度分不得大于 1");
        }
    }

    /* ================================================================== */
    /* 5. 评分                                                              */
    /* ================================================================== */

    @Nested
    @DisplayName("评分维度")
    class Rating {

        @Test
        @DisplayName("满分 5.00 → 1.0")
        void fullRatingShouldScoreOne() {
            DimensionResult result = new RatingMatchRule().evaluate(
                    candidate().rating(new BigDecimal("5.00")).build(),
                    target().build());

            assertEquals(1.0D, result.score(), 1e-9D);
            assertFalse(result.isDegraded());
        }

        @Test
        @DisplayName("评分 0.00 → 降级记 0（暂无评分，不是「评分很差」）")
        void zeroRatingShouldDegradeToZero() {
            DimensionResult result = new RatingMatchRule().evaluate(
                    candidate().rating(BigDecimal.ZERO).build(),
                    target().build());

            assertEquals(0.0D, result.score(), 1e-9D);
            assertTrue(result.isDegraded(), "0 分意味着没有评分，必须降级");
            assertTrue(result.note().contains("评分维度记 0"), "降级说明要写明：" + result.note());
        }

        @Test
        @DisplayName("评分超过 5 → 截断在 1.0，不得大于 1")
        void ratingBeyondFiveShouldBeCapped() {
            DimensionResult result = new RatingMatchRule().evaluate(
                    candidate().rating(new BigDecimal("6.00")).build(),
                    target().build());

            assertTrue(result.score() <= 1.0D, "维度分不得大于 1");
            assertEquals(1.0D, result.score(), 1e-9D);
        }

        @Test
        @DisplayName("评分 4.00 → 0.8")
        void ratingFourShouldScorePointEight() {
            DimensionResult result = new RatingMatchRule().evaluate(
                    candidate().rating(new BigDecimal("4.00")).build(),
                    target().build());

            assertEquals(0.8D, result.score(), 1e-9D, "4.00 / 5 = 0.8");
        }
    }

    /* ================================================================== */
    /* 6. 加权（纯函数）                                                    */
    /* ================================================================== */

    @Nested
    @DisplayName("加权总分")
    class Weighted {

        /** 默认权重：0.30 / 0.25 / 0.20 / 0.15 / 0.10 */
        private final MatchingWeights weights = new MatchingWeights(0.30D, 0.25D, 0.20D, 0.15D, 0.10D);

        @Test
        @DisplayName("同一组维度与权重 → 总分恒定（纯函数，不依赖调用顺序与时刻）")
        void weightedTotalShouldBeDeterministic() {
            Map<String, DimensionResult> dimensions = dimensions();

            double first = MatchingScore.weightedTotal(dimensions, weights);
            double second = MatchingScore.weightedTotal(dimensions, weights);

            assertEquals(first, second, 0.0D, "同一输入必须得到同一总分");
            // 0.30×1.0 + 0.25×0.5 + 0.20×0.0 + 0.15×0.2 + 0.10×1.0 = 0.555
            assertEquals(0.555D, first, 1e-9D);
        }

        @Test
        @DisplayName("权重和为 1 时，总分落在 [0, 1]")
        void weightedTotalShouldStayInUnitRange() {
            assertEquals(1.0D, weights.sum(), 1e-9D);

            Map<String, DimensionResult> allFull = new LinkedHashMap<>();
            for (String dimension : MatchingWeights.DIMENSIONS) {
                allFull.put(dimension, DimensionResult.of(1.0D, "满分"));
            }
            Map<String, DimensionResult> allZero = new LinkedHashMap<>();
            for (String dimension : MatchingWeights.DIMENSIONS) {
                allZero.put(dimension, DimensionResult.of(0.0D, "零分"));
            }

            assertEquals(1.0D, MatchingScore.weightedTotal(allFull, weights), 1e-9D);
            assertEquals(0.0D, MatchingScore.weightedTotal(allZero, weights), 1e-9D);
            assertTrue(MatchingScore.weightedTotal(dimensions(), weights) >= 0.0D);
            assertTrue(MatchingScore.weightedTotal(dimensions(), weights) <= 1.0D);
        }

        @Test
        @DisplayName("降级维度的说明进 notes，不进 reasons（别让人误以为「距离不匹配」）")
        void degradedDimensionShouldOnlyAppearInNotes() {
            Map<String, DimensionResult> dimensions = new LinkedHashMap<>();
            dimensions.put(MatchingWeights.DIMENSION_REGION, DimensionResult.of(1.0D, "同服务区域"));
            dimensions.put(MatchingWeights.DIMENSION_TIME, DimensionResult.of(1.0D, "就诊时间在可服务时段内"));
            dimensions.put(MatchingWeights.DIMENSION_DISTANCE,
                    DimensionResult.degraded(0.0D, "未填写坐标", "未填写坐标，距离维度记 0"));
            dimensions.put(MatchingWeights.DIMENSION_SKILL, DimensionResult.of(0.0D, "未服务过该科室"));
            dimensions.put(MatchingWeights.DIMENSION_RATING, DimensionResult.of(1.0D, "评分 4.8"));

            MatchingScore score = new MatchingScore(dimensions,
                    MatchingScore.weightedTotal(dimensions, weights));

            assertEquals(3, score.reasons().size(), "只有未降级且得分大于 0 的维度才有命中理由");
            assertFalse(score.reasons().contains("未填写坐标"), "降级维度不得混进命中理由");
            assertFalse(score.reasons().contains("未服务过该科室"), "0 分维度不是命中理由");
            assertEquals(1, score.notes().size());
            assertTrue(score.notes().get(0).contains("未填写坐标"));
        }

        @Test
        @DisplayName("0 分且非降级的维度进 mismatches，不进 reasons（淘汰理由不能说成推荐理由）")
        void zeroScoreDimensionShouldAppearInMismatchesNotReasons() {
            Map<String, DimensionResult> dimensions = new LinkedHashMap<>();
            dimensions.put(MatchingWeights.DIMENSION_REGION, DimensionResult.of(0.0D, "跨城市"));
            dimensions.put(MatchingWeights.DIMENSION_TIME, DimensionResult.of(0.5D, "同一城市不同区域"));
            dimensions.put(MatchingWeights.DIMENSION_DISTANCE, DimensionResult.of(0.0D, "超出服务半径"));
            dimensions.put(MatchingWeights.DIMENSION_SKILL,
                    DimensionResult.degraded(0.0D, "未登记可服务时段", "未登记可服务时段，时间维度记 0"));
            dimensions.put(MatchingWeights.DIMENSION_RATING, DimensionResult.of(1.0D, "评分 4.8"));

            MatchingScore score = new MatchingScore(dimensions,
                    MatchingScore.weightedTotal(dimensions, weights));

            assertEquals(List.of("同一城市不同区域", "评分 4.8"), score.reasons(),
                    "命中理由只收得分大于 0 的维度");
            assertEquals(List.of("跨城市", "超出服务半径"), score.mismatches(),
                    "0 分且非降级的维度归 mismatches");
            assertEquals(1, score.notes().size(), "降级维度仍归 notes");
            assertFalse(score.reasons().contains("跨城市"), "「跨城市」是淘汰理由，不得混进推荐理由");
            assertFalse(score.mismatches().contains("未登记可服务时段"), "降级不是没命中");
        }

        @Test
        @DisplayName("权重表：已知维度取对应权重，未知维度取 0（多一条规则不会让引擎崩）")
        void weightOfShouldBeKeyedByDimension() {
            assertEquals(0.30D, weights.weightOf(MatchingWeights.DIMENSION_REGION), 1e-9D);
            assertEquals(0.25D, weights.weightOf(MatchingWeights.DIMENSION_TIME), 1e-9D);
            assertEquals(0.20D, weights.weightOf(MatchingWeights.DIMENSION_DISTANCE), 1e-9D);
            assertEquals(0.15D, weights.weightOf(MatchingWeights.DIMENSION_SKILL), 1e-9D);
            assertEquals(0.10D, weights.weightOf(MatchingWeights.DIMENSION_RATING), 1e-9D);
            assertEquals(0.0D, weights.weightOf("unknown"), 1e-9D);
        }
    }

    /* ================================================================== */
    /* 7. 自由文本时段解析（回退路径）                                      */
    /* ================================================================== */

    @Nested
    @DisplayName("自由文本时段解析")
    class LegacySlots {

        @Test
        @DisplayName("「周一至周五 08:00-18:00」→ 周一至周五各一个时段")
        void shouldParseWeekdayRange() {
            List<MatchingCandidate.AvailableSlot> slots =
                    LegacySlotParser.parse("周一至周五 08:00-18:00");

            assertEquals(5, slots.size());
            assertEquals(List.of(1, 2, 3, 4, 5),
                    slots.stream().map(MatchingCandidate.AvailableSlot::weekday).toList());
            assertEquals(LocalTime.of(8, 0), slots.get(0).start());
            assertEquals(LocalTime.of(18, 0), slots.get(0).end());
        }

        @Test
        @DisplayName("「全周 07:00-20:00」→ 7 天；「周末 09:00-17:00」→ 周六 + 周日")
        void shouldParseWholeWeekAndWeekend() {
            assertEquals(7, LegacySlotParser.parse("全周 07:00-20:00").size());
            assertEquals(List.of(6, 7), LegacySlotParser.weekdaysOf("周末 09:00-17:00"));
        }

        @Test
        @DisplayName("解析不出来 → 空列表（宁可记 0 也不猜）")
        void shouldReturnEmptyWhenUnparseable() {
            assertTrue(LegacySlotParser.parse("时间待定").isEmpty());
            assertTrue(LegacySlotParser.parse(null).isEmpty());
            assertTrue(LegacySlotParser.parse("").isEmpty());
            assertTrue(LegacySlotParser.weekdaysOf("随时上门").isEmpty());
        }
    }

    /* ================================================================== */
    /* 夹具                                                                */
    /* ================================================================== */

    /** 维度得分表（加权用） */
    private static Map<String, DimensionResult> dimensions() {
        Map<String, DimensionResult> dimensions = new LinkedHashMap<>();
        dimensions.put(MatchingWeights.DIMENSION_REGION, DimensionResult.of(1.0D, "同服务区域"));
        dimensions.put(MatchingWeights.DIMENSION_TIME, DimensionResult.of(0.5D, "当天可服务，但就诊时刻不在时段内"));
        dimensions.put(MatchingWeights.DIMENSION_DISTANCE, DimensionResult.of(0.0D, "超出服务半径"));
        dimensions.put(MatchingWeights.DIMENSION_SKILL, DimensionResult.of(0.2D, "服务过该科室 4 单"));
        dimensions.put(MatchingWeights.DIMENSION_RATING, DimensionResult.of(1.0D, "评分 5"));
        return dimensions;
    }

    private static CandidateBuilder candidate() {
        return new CandidateBuilder();
    }

    private static TargetBuilder target() {
        return new TargetBuilder();
    }

    /** 候选人快照构造器（默认值 = 「什么都没填」） */
    private static final class CandidateBuilder {
        private String regionCode;
        private String serviceArea;
        private BigDecimal longitude;
        private BigDecimal latitude;
        private final List<MatchingCandidate.AvailableSlot> slots = new java.util.ArrayList<>();
        private Map<String, Integer> departmentCounts = Map.of();
        private BigDecimal rating;

        CandidateBuilder regionCode(String regionCode) {
            this.regionCode = regionCode;
            return this;
        }

        CandidateBuilder serviceArea(String serviceArea) {
            this.serviceArea = serviceArea;
            return this;
        }

        CandidateBuilder longitude(BigDecimal longitude) {
            this.longitude = longitude;
            return this;
        }

        CandidateBuilder latitude(BigDecimal latitude) {
            this.latitude = latitude;
            return this;
        }

        CandidateBuilder slot(int weekday, LocalTime start, LocalTime end) {
            slots.add(new MatchingCandidate.AvailableSlot(weekday, start, end));
            return this;
        }

        CandidateBuilder departmentCounts(Map<String, Integer> departmentCounts) {
            this.departmentCounts = departmentCounts;
            return this;
        }

        CandidateBuilder rating(BigDecimal rating) {
            this.rating = rating;
            return this;
        }

        MatchingCandidate build() {
            return new MatchingCandidate(301L, regionCode, serviceArea, longitude, latitude,
                    List.copyOf(slots), departmentCounts, rating);
        }
    }

    /** 订单快照构造器（默认值 = 「什么都没填」） */
    private static final class TargetBuilder {
        private String regionCode;
        private String address;
        private LocalDateTime visitTime;
        private BigDecimal longitude;
        private BigDecimal latitude;
        private String department;

        TargetBuilder regionCode(String regionCode) {
            this.regionCode = regionCode;
            return this;
        }

        TargetBuilder address(String address) {
            this.address = address;
            return this;
        }

        TargetBuilder visitTime(LocalDateTime visitTime) {
            this.visitTime = visitTime;
            return this;
        }

        TargetBuilder longitude(BigDecimal longitude) {
            this.longitude = longitude;
            return this;
        }

        TargetBuilder latitude(BigDecimal latitude) {
            this.latitude = latitude;
            return this;
        }

        TargetBuilder department(String department) {
            this.department = department;
            return this;
        }

        MatchTarget build() {
            return new MatchTarget(2001L, regionCode, address, visitTime, longitude, latitude, department);
        }
    }
}

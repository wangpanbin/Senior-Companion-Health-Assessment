package org.company.nianglin.config;

import org.company.nianglin.service.support.matching.MatchingWeights;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V3.4 匹配权重的<b>启动期校验</b>单测（T3 · FR-Config）。
 *
 * <h3>为什么这类校验值得单测</h3>
 *
 * <p>错误的权重<b>不会报错</b>，只会让排序悄悄变得不合理 ——
 * 那是生产上最难定位的一类故障。所以约定是「启动失败」：
 * 错的配置要在启动的十秒内暴露，而不是等运营抱怨「推荐不准」。</p>
 *
 * <p>本类就放在 {@code org.company.nianglin.config} 包下，
 * 因为 {@code MatchingProperties#validate()} 是包级可见的
 * （{@code @PostConstruct} 不该被外部直接调用，但测试要能真的把它调起来）。</p>
 *
 * <h3>反向验证</h3>
 *
 * <p>每个「配置写坏」的用例都断言<b>具体的失败原因</b>：
 * 权重和不对 → 消息含「权重之和必须等于 1」；
 * 权重为负 → 消息含「不能为负」；
 * 量程为 0 → 消息含对应的配置键名。
 * 只断言「抛了异常」的话，把校验顺序改错了也发现不了。</p>
 *
 * @author 银龄伴诊团队
 * @since V3.4
 */
@DisplayName("V3.4 匹配权重配置：权重和为 1 才允许启动")
class MatchingWeightConfigTest {

    /* ================================================================== */
    /* 1. 默认配置合法                                                      */
    /* ================================================================== */

    @Test
    @DisplayName("默认权重 0.30/0.25/0.20/0.15/0.10 之和为 1，启动校验通过")
    void defaultWeightsShouldPassValidation() {
        MatchingProperties properties = new MatchingProperties();

        assertDoesNotThrow(properties::validate, "默认权重必须能通过启动校验");

        double sum = properties.getRegionWeight() + properties.getTimeWeight()
                + properties.getDistanceWeight() + properties.getSkillWeight()
                + properties.getRatingWeight();
        assertEquals(1.0D, sum, 1e-9D, "五个权重之和必须正好是 1");
        assertEquals(0.30D, properties.getRegionWeight(), 1e-9D);
        assertEquals(0.25D, properties.getTimeWeight(), 1e-9D);
        assertEquals(0.20D, properties.getDistanceWeight(), 1e-9D);
        assertEquals(0.15D, properties.getSkillWeight(), 1e-9D);
        assertEquals(0.10D, properties.getRatingWeight(), 1e-9D);
    }

    @Test
    @DisplayName("默认量程合法：距离满量程 30000 米、服务能力满量程 20 单、候选集上限 500")
    void defaultScalesShouldBePositive() {
        MatchingProperties properties = new MatchingProperties();

        assertTrue(properties.getDistanceMaxMeters() > 0, "距离满量程必须为正");
        assertTrue(properties.getSkillMaxOrders() > 0, "服务能力满量程必须为正");
        assertTrue(properties.getHallCandidateLimit() > 0, "大厅候选集上限必须为正");
    }

    /* ================================================================== */
    /* 2. 改坏权重 → 启动失败                                              */
    /* ================================================================== */

    @Test
    @DisplayName("权重和变成 1.20（region 0.30 → 0.50）→ 启动失败")
    void brokenWeightSumShouldFailFast() {
        MatchingProperties properties = new MatchingProperties();
        properties.setRegionWeight(0.50D); // 0.30 → 0.50，和变成 1.20

        IllegalStateException ex = assertThrows(IllegalStateException.class, properties::validate);

        assertTrue(ex.getMessage().contains("权重之和必须等于 1"),
                "错误消息要说明是权重和不对：" + ex.getMessage());
    }

    @Test
    @DisplayName("权重和不足 1 → 同样启动失败（不静默归一化）")
    void tooSmallWeightSumShouldFailFast() {
        MatchingProperties properties = new MatchingProperties();
        properties.setRatingWeight(0.05D); // 和变成 0.95

        IllegalStateException ex = assertThrows(IllegalStateException.class, properties::validate);

        assertTrue(ex.getMessage().contains("权重之和必须等于 1"),
                "错误消息要说明是权重和不对：" + ex.getMessage());
    }

    @Test
    @DisplayName("权重为负 → 启动失败（且必须走到「负数」这条判定，而不是被和的判定先拦下）")
    void negativeWeightShouldFailFast() {
        MatchingProperties properties = new MatchingProperties();
        // 先把和凑回 1，让校验走到「权重不能为负」那一步
        properties.setRegionWeight(-0.10D);
        properties.setTimeWeight(0.35D);
        properties.setDistanceWeight(0.25D);
        properties.setSkillWeight(0.25D);
        properties.setRatingWeight(0.25D);

        IllegalStateException ex = assertThrows(IllegalStateException.class, properties::validate);

        assertTrue(ex.getMessage().contains("不能为负"),
                "错误消息要说明是权重为负：" + ex.getMessage());
    }

    /* ================================================================== */
    /* 3. 改坏量程 → 启动失败                                              */
    /* ================================================================== */

    @Test
    @DisplayName("distance-max-meters <= 0 → 启动失败")
    void nonPositiveDistanceScaleShouldFailFast() {
        MatchingProperties properties = new MatchingProperties();
        properties.setDistanceMaxMeters(0);

        IllegalStateException ex = assertThrows(IllegalStateException.class, properties::validate);

        assertTrue(ex.getMessage().contains("distance-max-meters"),
                "错误消息要指明是哪个配置项：" + ex.getMessage());
    }

    @Test
    @DisplayName("skill-max-orders <= 0 → 启动失败")
    void nonPositiveSkillScaleShouldFailFast() {
        MatchingProperties properties = new MatchingProperties();
        properties.setSkillMaxOrders(-1);

        IllegalStateException ex = assertThrows(IllegalStateException.class, properties::validate);

        assertTrue(ex.getMessage().contains("skill-max-orders"),
                "错误消息要指明是哪个配置项：" + ex.getMessage());
    }

    @Test
    @DisplayName("hall-candidate-limit <= 0 → 启动失败")
    void nonPositiveHallCandidateLimitShouldFailFast() {
        MatchingProperties properties = new MatchingProperties();
        properties.setHallCandidateLimit(0);

        IllegalStateException ex = assertThrows(IllegalStateException.class, properties::validate);

        assertTrue(ex.getMessage().contains("hall-candidate-limit"),
                "错误消息要指明是哪个配置项：" + ex.getMessage());
    }

    /* ================================================================== */
    /* 4. MatchingWeights 与配置口径一致                                    */
    /* ================================================================== */

    @Test
    @DisplayName("MatchingWeights.isNormalized() 与 sum() 口径一致：和为 1 → 合法")
    void normalizedShouldFollowSum() {
        MatchingWeights weights = new MatchingWeights(0.30D, 0.25D, 0.20D, 0.15D, 0.10D);

        assertEquals(1.0D, weights.sum(), 1e-9D);
        assertTrue(weights.isNormalized(), "和为 1 必须判定为已归一");
    }

    @Test
    @DisplayName("MatchingWeights.isNormalized() 与 sum() 口径一致：和不为 1 → 不合法")
    void notNormalizedShouldFollowSum() {
        MatchingWeights broken = new MatchingWeights(0.50D, 0.25D, 0.20D, 0.15D, 0.10D);

        assertEquals(1.20D, broken.sum(), 1e-9D);
        assertFalse(broken.isNormalized(), "和不为 1 必须判定为未归一");
    }

    @Test
    @DisplayName("从配置构造出的权重是已归一的（Service 层拿到的就是这份）")
    void weightsBuiltFromPropertiesShouldBeNormalized() {
        MatchingProperties properties = new MatchingProperties();

        MatchingWeights weights = new MatchingWeights(
                properties.getRegionWeight(),
                properties.getTimeWeight(),
                properties.getDistanceWeight(),
                properties.getSkillWeight(),
                properties.getRatingWeight());

        assertTrue(weights.isNormalized());
        assertEquals(5, weights.asMap().size(), "权重表必须覆盖全部五个维度");
        assertEquals(0.30D, weights.asMap().get(MatchingWeights.DIMENSION_REGION), 1e-9D);
    }
}

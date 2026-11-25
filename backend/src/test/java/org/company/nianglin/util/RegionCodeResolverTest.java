package org.company.nianglin.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 区域码解析单测（V3.4 结构化匹配的运行期写入点）。
 *
 * <h3>本测试存在的理由</h3>
 *
 * <p>V7 的 backfill 只回填了「当时库里已有」的数据。此后新下的订单、新提交的资质
 * 若不在写入时解析区域码，{@code service_region_code} 恒为 {@code null}，
 * 区域维度只能走自由文本回退 —— 而那只有「命中 1.0 / 不命中 0」两档，
 * <b>算不出 V3.4 设计的「同市不同区 = 0.5」</b>。这条失效链没有任何报错，
 * 只会让候选排序悄悄变差，所以必须用测试钉死。</p>
 *
 * <h3>与 V7 backfill 的「逐字同构」是本类的核心断言</h3>
 *
 * <p>V7 是一串顺序执行的
 * {@code UPDATE ... WHERE service_region_code IS NULL AND <列> LIKE '%<关键词>%'}。
 * {@code IS NULL} 意味着<b>先执行的赢</b>。所以一条地址若同时含两个区县名，
 * 结果取决于<b>映射文件里的顺序</b>。本类用
 * {@code 「海口市美兰区…琼山区…」→ HAIKOU_MEILAN（文件里美兰在前）} 把这条语义钉住 ——
 * 一旦有人把实现改成「最长关键词优先」或「最后一个命中」，回填出来的老数据
 * 与新写入的数据就会在同一份地址上得出不同区域码，同一张表里出现两种口径。</p>
 *
 * @author 银龄伴诊团队
 * @since V3.4（运行期写入点：2026-11-25 补）
 */
class RegionCodeResolverTest {

    /* ================================================================== */
    /* 1 · 正常解析                                                        */
    /* ================================================================== */

    @ParameterizedTest(name = "{0} → {1}")
    @DisplayName("解析 · 八个区县名的典型地址形态都能解析出正确区域码")
    @CsvSource({
            "海南省海口市美兰区人民大道43号, HAIKOU_MEILAN",
            "海南省海口市龙华区龙昆北路12号, HAIKOU_LONGHUA",
            "海南省海口市秀英区秀华路19号, HAIKOU_XIUYING",
            "海南省海口市琼山区府城镇, HAIKOU_QONGSHAN",
            "海南省三亚市吉阳区迎宾路, SANYA_JIYANG",
            "海南省三亚市天涯区解放路, SANYA_TIANYA",
            "海南省儋州市那大镇中兴大道, DANZHOU_NADA",
            "海南省琼海市嘉积镇人民路, QIONGHAI_JIJI",
    })
    void shouldResolveTypicalAddresses(String address, String expected) {
        assertThat(RegionCodeResolver.resolve(address)).isEqualTo(expected);
    }

    @Test
    @DisplayName("解析 · 陪诊员只填「区名」这种短形态也能解析（自由文本回退做不到）")
    void shouldResolveBareDistrictName() {
        // 自由文本回退要求「service_area 整串出现在订单地址里」，
        // 「美兰区」能命中，但「海口市美兰区」在「海口美兰区…」这种写法下就命不中 ——
        // 区域码解析是按关键词命中，不受整串限制
        assertThat(RegionCodeResolver.resolve("美兰区")).isEqualTo("HAIKOU_MEILAN");
        assertThat(RegionCodeResolver.resolve("海口美兰区人民大道43号")).isEqualTo("HAIKOU_MEILAN");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t\n"})
    @DisplayName("解析 · null / 空串 / 纯空白一律返回 null，不抛异常")
    void shouldReturnNullForBlank(String text) {
        assertThat(RegionCodeResolver.resolve(text)).isNull();
    }

    @Test
    @DisplayName("解析 · 地址里没有区县名时返回 null（「映射不上的留空」与 V7 同口径，绝不猜）")
    void shouldReturnNullWhenNoDistrictMatched() {
        assertThat(RegionCodeResolver.resolve("海南省某市某路 100 号")).isNull();
        assertThat(RegionCodeResolver.resolve("协和医院")).isNull();
    }

    /* ================================================================== */
    /* 2 · 与 V7 backfill 逐字同构：先执行的赢                              */
    /* ================================================================== */

    @Test
    @DisplayName("多个区县名同时出现时，按映射文件顺序取第一个（与 V7 的 IS NULL 先到先得一致）")
    void shouldResolveByFileOrderWhenMultipleDistrictsPresent() {
        // 映射文件里 HAIKOU_MEILAN 排在 HAIKOU_QONGSHAN 之前
        String ambiguous = "海南省海口市美兰区某某路（近琼山区边界）";
        assertThat(RegionCodeResolver.resolve(ambiguous)).isEqualTo("HAIKOU_MEILAN");

        // 反过来排也一样：龙华区在文件里排在秀英区之前
        String another = "海南省海口市龙华区某某路（近秀英区）";
        assertThat(RegionCodeResolver.resolve(another)).isEqualTo("HAIKOU_LONGHUA");
    }

    /* ================================================================== */
    /* 3 · 映射表本身                                                      */
    /* ================================================================== */

    @Test
    @DisplayName("映射表 · 恰好 8 条区域码，顺序与 region_code_mapping.json 一致")
    void shouldLoadExactlyEightRegions() {
        assertThat(RegionCodeResolver.allCodes()).containsExactly(
                "HAIKOU_MEILAN", "HAIKOU_LONGHUA", "HAIKOU_XIUYING", "HAIKOU_QONGSHAN",
                "SANYA_JIYANG", "SANYA_TIANYA", "DANZHOU_NADA", "QIONGHAI_JIJI");
    }

    @Test
    @DisplayName("映射表 · 每条都是 <CITY>_<DISTRICT> 形态，城市前缀可被 cityOf 取出")
    void shouldExposeCityPrefix() {
        for (String code : RegionCodeResolver.allCodes()) {
            assertThat(code).contains("_");
            assertThat(RegionCodeResolver.cityOf(code)).isEqualTo(code.split("_", 2)[0]);
        }
        assertThat(RegionCodeResolver.cityOf("HAIKOU_MEILAN")).isEqualTo("HAIKOU");
        assertThat(RegionCodeResolver.cityOf("haikou_meilan")).isEqualTo("HAIKOU");
        assertThat(RegionCodeResolver.cityOf(null)).isNull();
    }

    /* ================================================================== */
    /* 4 · 与匹配规则包的口径一致（防两处各写一份）                        */
    /* ================================================================== */

    @Test
    @DisplayName("cityOf 与 RegionMatchRule.cityOf 对同一批区域码给出相同结果")
    void cityOfShouldAgreeWithMatchRule() {
        List<String> codes = RegionCodeResolver.allCodes();
        for (String code : codes) {
            assertThat(RegionCodeResolver.cityOf(code))
                    .as("区域码 %s 的城市前缀在两处必须一致", code)
                    .isEqualTo(
                            org.company.nianglin.service.support.matching.RegionMatchRule.cityOf(code));
        }
    }
}

package org.company.nianglin.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 陪诊员评分聚合（{@code docs/api/06-review-complaint.md} §4）。
 *
 * <h3>数字口径必须与手工 SQL 一致</h3>
 *
 * <p>验收项写的是「陪诊员平均分 = {@code SELECT ROUND(AVG(score), 2)} 手工核对一致」。
 * 这条要求实际上是在约束<b>实现方式</b>：平均值必须由数据库算，
 * 而不是把整页评价拉到内存里 {@code stream().average()}。
 * 后者在评价变多以后会随着页码变化而抖动，而且受「分页只取了一部分」影响，
 * 算出来的根本不是全量平均分。</p>
 *
 * <h3>无评价时返回 0 而不是 null</h3>
 *
 * <p>{@code averageScore = "0.00"}、{@code totalCount = 0}。
 * 新陪诊员的资料页第一次被打开时就是这个状态，这是<b>正常</b>而非异常，
 * 让前端为它写「如果 averageScore 是 null 就显示—」的分支，
 * 只会让这段逻辑在别处被复制。</p>
 *
 * @author 银龄伴诊团队
 * @since M7
 */
@Data
@Accessors(chain = true)
@Schema(description = "陪诊员评分聚合")
public class CompanionScoreVO {

    @Schema(description = "陪诊员用户 ID", example = "10088")
    private Long companionId;

    @Schema(description = "平均分，两位小数；无评价时为 0.00", example = "4.80")
    private String averageScore;

    @Schema(description = "有效评价总数（不含管理员判定无效的）", example = "37")
    private Integer totalCount;

    @Schema(description = "星级分布，键为 1–5", example = "{\"5\":30,\"4\":5,\"3\":1,\"2\":0,\"1\":1}")
    private Map<String, Long> starDistribution;

    @Schema(description = "好评率（4 星及以上占比），两位小数百分比", example = "94.59%")
    private String goodRate;

    /**
     * 装配。
     *
     * @param companionId  陪诊员用户 ID
     * @param average      数据库算出的平均分（{@code ROUND(AVG(score), 2)}），无评价时为 {@code null}
     * @param total        有效评价总数
     * @param distribution 星级分布，键为 1–5；缺失的星级补 0
     */
    public static CompanionScoreVO of(Long companionId, BigDecimal average, long total,
                                      Map<Integer, Long> distribution) {
        Map<String, Long> stars = new LinkedHashMap<>(5);
        long good = 0L;
        // 固定从 5 到 1 输出，且缺失星级补 0：
        // 前端要画五个等宽的柱子，缺键会让「3 星那根柱子」的位置错位
        for (int star = 5; star >= 1; star--) {
            long count = distribution == null ? 0L : distribution.getOrDefault(star, 0L);
            stars.put(String.valueOf(star), count);
            if (star >= 4) {
                good += count;
            }
        }

        return new CompanionScoreVO()
                .setCompanionId(companionId)
                .setAverageScore(average == null
                        ? "0.00"
                        : average.setScale(2, RoundingMode.HALF_UP).toPlainString())
                .setTotalCount((int) total)
                .setStarDistribution(stars)
                .setGoodRate(rate(good, total));
    }

    private static String rate(long good, long total) {
        if (total <= 0) {
            return "0.00%";
        }
        return BigDecimal.valueOf(good)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP)
                .toPlainString() + "%";
    }
}

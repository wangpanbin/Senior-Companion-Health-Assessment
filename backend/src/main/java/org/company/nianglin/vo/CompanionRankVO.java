package org.company.nianglin.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;
import org.company.nianglin.util.MaskUtil;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 陪诊员排行项（{@code docs/api/09-statistics-export.md} §4）。
 *
 * <p>姓名脱敏：排行是管理端内部的绩效视图，用来发现「谁接得多、谁评分高」，
 * 不需要精确到人称；而这一页往往是在会议投屏上打开的第一个页面。</p>
 *
 * <p>{@code rank} 由服务端给出而不是让前端按数组下标 +1：
 * 一旦将来出现并列名次（同分同单量），前端算出来的名次会与服务端的排序口径
 * 悄悄脱节，而并列的处理规则只有服务端知道。</p>
 *
 * @author 银龄伴诊团队
 * @since M10
 */
@Data
@Accessors(chain = true)
@Schema(description = "陪诊员排行项")
public class CompanionRankVO {

    @Schema(description = "名次，从 1 开始", example = "1")
    private Integer rank;

    @Schema(description = "陪诊员用户 ID", example = "10088")
    private Long companionId;

    @Schema(description = "陪诊员姓名（脱敏）", example = "李*")
    private String companionName;

    @Schema(description = "区间内接单数", example = "22")
    private Integer orderCount;

    @Schema(description = "区间内完成数（含已评价）", example = "21")
    private Integer completedCount;

    @Schema(description = "完成率", example = "95.45%")
    private String completedRate;

    @Schema(description = "当前评分（两位小数字符串）", example = "4.91")
    private String score;

    /**
     * 装配。
     *
     * @param rank           名次
     * @param companionId    陪诊员用户 ID
     * @param companionName  姓名原文，本方法内脱敏
     * @param orderCount     接单数
     * @param completedCount 完成数
     * @param score          评分（可为 {@code null}，表示还没有有效评价）
     */
    public static CompanionRankVO of(int rank, Long companionId, String companionName,
                                     long orderCount, long completedCount, BigDecimal score) {
        return new CompanionRankVO()
                .setRank(rank)
                .setCompanionId(companionId)
                .setCompanionName(MaskUtil.name(companionName))
                .setOrderCount((int) orderCount)
                .setCompletedCount((int) completedCount)
                .setCompletedRate(ChartDataVO.percent(completedCount, orderCount))
                .setScore(score == null ? "0.00" : score.setScale(2, RoundingMode.HALF_UP).toPlainString());
    }
}

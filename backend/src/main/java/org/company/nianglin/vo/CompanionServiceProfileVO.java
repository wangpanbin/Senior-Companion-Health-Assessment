package org.company.nianglin.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.ArrayList;
import java.util.List;

/**
 * 陪诊员服务画像出参（V3.3 · FR-Profile）。
 *
 * <h3>口径：历史累计</h3>
 *
 * <p>本 VO 回答的是「我到底什么水平」，所以除特别说明外都是<b>历史累计</b>口径，
 * <b>不随统计区间浮动</b>。这与 {@link CompanionWorkbenchOverviewVO} 的区间口径
 * 是两套不同的东西，字段名已自带口径（{@code total*} 前缀 = 累计）。</p>
 *
 * <p>⚠️ 指标口径已登记在指标字典（{@code companion.profile.*}）。</p>
 *
 * @author 银龄伴诊团队
 * @since V3.3
 */
@Data
@Accessors(chain = true)
@Schema(description = "陪诊员服务画像（累计口径）")
public class CompanionServiceProfileVO {

    @Schema(description = "平均评分（累计，取自陪诊员档案，不重算）", example = "4.80")
    private String averageScore;

    @Schema(description = "累计完成单数", example = "128")
    private Integer totalCompletedOrderCount;

    @Schema(description = "累计服务过的不同老人数", example = "46")
    private Integer totalServedElderCount;

    @Schema(description = "累计准时率，百分数字符串两位小数", example = "92.00%")
    private String punctualityRate;

    @Schema(description = "累计完成率，百分数字符串两位小数", example = "93.00%")
    private String totalCompletionRate;

    @Schema(description = "累计服务时长（分钟）", example = "8640")
    private Integer totalServiceDurationMinutes;

    @Schema(description = "擅长服务（按科室统计的 TOP N，样本不足 5 单的科室不展示）")
    private List<CompanionExpertiseVO> expertise = new ArrayList<>();
}

package org.company.nianglin.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 陪诊员工作台概览出参（V3.3 · FR-Overview）。
 *
 * <h3>口径：统计区间内</h3>
 *
 * <p>本 VO 的每个指标都是<b>统计区间内</b>的口径，区间由
 * {@code dto.DateRangeQuery} 决定（默认最近 30 天、上限 366 天、闭区间）。</p>
 *
 * <p>⚠️ 与 {@link CompanionServiceProfileVO} 的<b>累计</b>口径是两套不同的东西，
 * 不要混用：这里回答「这段时间做得怎么样」，画像回答「我到底什么水平」。
 * 两边都叫「完成率」的地方，字段名已经自带口径
 * （本 VO 是 {@code completionRate}，画像是 {@code totalCompletionRate}）。</p>
 *
 * <p>⚠️ 指标口径已登记在指标字典（{@code companion.workbench.*}），
 * 改公式必须同步改字典并升 {@code caliberVersion}。</p>
 *
 * @author 银龄伴诊团队
 * @since V3.3
 */
@Data
@Accessors(chain = true)
@Schema(description = "陪诊员工作台概览（区间口径）")
public class CompanionWorkbenchOverviewVO {

    @Schema(description = "统计区间开始日（含）", example = "2026-09-21")
    private String periodStart;

    @Schema(description = "统计区间结束日（含）", example = "2026-10-20")
    private String periodEnd;

    @Schema(description = "区间完成单数（COMPLETED + REVIEWED）", example = "28")
    private Integer completedOrderCount;

    @Schema(description = "区间服务时长（分钟）", example = "2520")
    private Integer serviceDurationMinutes;

    @Schema(description = "区间完成率，百分数字符串两位小数", example = "93.00%")
    private String completionRate;

    @Schema(description = "平均评分（累计口径，取自陪诊员档案，不重算）", example = "4.80")
    private String averageScore;

    @Schema(description = "区间收入（元）", example = "3680.00")
    private String periodIncome;
}

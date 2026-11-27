package org.company.nianglin.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.ArrayList;
import java.util.List;

/**
 * 一条指标的定义（V3.6 · T7 · FR-Trace）。
 *
 * <h3>它导出的是「口径」，不是「值」</h3>
 *
 * <p>本 VO 的每个字段都来自指标字典的机器面
 * （{@code metrics/metric-dictionary.yml}），回答的是
 * 「这个数<b>怎么算</b>、统计<b>谁</b>、取自<b>哪张表</b>、口径第几版」，
 * 而<b>不包含任何统计值</b>。</p>
 *
 * <p>这个区分很重要：运营拿它对账（驾驶舱上的数对不对得上定义），
 * 而不是拿它看板（数在哪里看）。所以本 VO <b>不</b>进
 * {@code metrics/endpoints.yml} —— 那张表登记的是「哪个端点返回了哪些指标<b>值</b>」。</p>
 *
 * @author 银龄伴诊团队
 * @since V3.6
 */
@Data
@Accessors(chain = true)
@Schema(description = "一条指标的定义（五要素 + 口径版本 + 出口）")
public class MetricDefinitionVO {

    @Schema(description = "指标 key（全局唯一，字典里的身份证）", example = "operations.fulfillment_rate")
    private String key;

    @Schema(description = "指标名称", example = "履约率")
    private String name;

    @Schema(description = "统计范围", example = "统计区间内进入终态的陪诊订单")
    private String scope;

    @Schema(description = "统计时间", example = "统计区间（闭区间，默认最近 30 天）")
    private String period;

    @Schema(description = "数据来源（表与字段）", example = "companion_order(status, create_time)")
    private String source;

    @Schema(description = "计算公式", example = "(COMPLETED + REVIEWED) / (COMPLETED + REVIEWED + CANCELLED)")
    private String formula;

    @Schema(description = "口径版本（口径变更即 +1，不改写历史数据）", example = "1")
    private Integer caliberVersion;

    @Schema(description = "返回该指标的端点（端点 ↔ 指标机器面）",
            example = "[\"GET /api/admin/operations/overview\"]")
    private List<String> endpoints = new ArrayList<>();

    /**
     * 该 key 背后的计算器主 key；{@code null} 表示**没有计算器**（只能读、不能算）。
     *
     * <p>为什么字典需要这个字段（V6.0 · 最终复盘 FE-05）：运营驾驶舱的「效果对比」
     * 只接受<b>已注册计算器</b>的指标，传其它 key 后端返回 400。此前前端只能自己
     * 抄一份 13 个 key 的白名单 —— 后端注册表增删一个指标，前端不会知道。
     * 现在字典直接告诉调用方「这个 key 能不能算」，前端不必再维护第二份清单。</p>
     *
     * <p>别名 key（同一口径的第二个名字）会返回<b>同一个</b> {@code calculatorKey}，
     * 前端据此去重即可，不再需要知道哪几个是别名。</p>
     */
    @Schema(description = "该指标的计算器主 key；null 表示没有计算器（不可用于效果对比）",
            example = "operations.fulfillment_rate")
    private String calculatorKey;
}

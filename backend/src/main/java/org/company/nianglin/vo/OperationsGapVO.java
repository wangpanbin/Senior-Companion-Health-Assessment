package org.company.nianglin.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.ArrayList;
import java.util.List;

/**
 * 供给时段缺口分析（V3.6 · T4 · FR-Gap）。
 *
 * <h3>桶固定 7 × 24 = 168 个</h3>
 *
 * <p>桶是「周内第几天 × 小时」，<b>与统计区间长度无关</b>：
 * 回溯 7 天还是 30 天，桶的形状都一样。这正是迭代计划 §47 那个闭环的数据基础 ——
 * 「订单无人接 → 统计发现 14:00–16:00 陪诊员数量不足 → 调整排班 → 下一周期重新统计」，
 * 只有桶固定，前后两期的数字才能直接对比。</p>
 *
 * <p><b>空桶也返回</b>（计数为 0），不返回缺行 ——
 * 缺行会让前端的热力网格出现空洞，而「这个时段没有缺口」和「这个时段没数据」
 * 在网格上长得一模一样。</p>
 *
 * <h3>供给数的口径（偏差 D-3.6-1）</h3>
 *
 * <p>{@code availableCompanions} 是「该时段<b>可服务</b>的陪诊员数」，
 * 按陪诊员自己的可服务时段分桶，而不是「当前工作状态为 AVAILABLE 的总人数」——
 * 后者在每个桶里都是同一个数，这个指标就没有意义。</p>
 *
 * @author 银龄伴诊团队
 * @since V3.6
 */
@Data
@Accessors(chain = true)
@Schema(description = "供给时段缺口分析（7×24 = 168 个桶，空桶也返回）")
public class OperationsGapVO {

    @Schema(description = "统计区间开始日（含）", example = "2026-09-21")
    private String periodStart;

    @Schema(description = "统计区间结束日（含）", example = "2026-10-20")
    private String periodEnd;

    @Schema(description = "返回的桶数（固定 168 = 7 天 × 24 小时）", example = "168")
    private int total;

    @Schema(description = "没有可解析可服务时段的陪诊员数（不参与供给统计，如实披露）", example = "7")
    private int companionsWithoutSlots;

    @Schema(description = "逐桶明细（按周内第几天 → 小时升序）")
    private List<Item> items = new ArrayList<>();

    /**
     * 一个「周内第几天 × 小时」的桶。
     */
    @Data
    @Accessors(chain = true)
    @Schema(description = "一个时段桶")
    public static class Item {

        @Schema(description = "周内第几天：1=周一 … 7=周日", example = "6")
        private int weekday;

        @Schema(description = "小时：0–23", example = "14")
        private int hour;

        @Schema(description = "该时段内下单但至今无人接的订单数（按 visit_time 落桶）", example = "3")
        private int pendingUnaccepted;

        @Schema(description = "该时段可服务的陪诊员数（同一人在同一桶里只计 1 次）", example = "1")
        private int availableCompanions;

        @Schema(description = "缺口 = 待接未接单数 − 可服务陪诊员数（> 0 即为缺口）", example = "2")
        private int gap;
    }
}

package org.company.nianglin.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 平台运营驾驶舱概览（V3.5 · T3）。
 *
 * <h3>同名不同型：一个必须说清的坑</h3>
 *
 * <p>本 VO 的 {@code avgAcceptMinutes} 是 {@link Integer}（分钟），
 * 而 {@link OperationsHealthVO} 的同名字段是 {@link String}。</p>
 *
 * <p>这不是笔误，是两个端点的渲染方式不同：概览里的卡片要参与数值比较
 * （比如「接单速度比上周快了 2 分钟」），健康度里的六项要统一渲染成一个整体。
 * 前端<b>不要</b>写一个通用的取值函数 —— 按各自的 VO 处理。</p>
 *
 * @author 银龄伴诊团队
 * @since V3.5
 */
@Data
@Accessors(chain = true)
@Schema(description = "平台运营驾驶舱概览")
public class OperationsOverviewVO {

    @Schema(description = "统计区间开始日（含）", example = "2026-09-21")
    private String periodStart;

    @Schema(description = "统计区间结束日（含）", example = "2026-10-20")
    private String periodEnd;

    @Schema(description = "今日新增订单数（不随统计区间浮动）", example = "128")
    private Integer todayOrderCount;

    @Schema(description = "区间内完成订单数（COMPLETED + REVIEWED）", example = "113")
    private Integer completedOrderCount;

    @Schema(description = "区间内服务中订单数（IN_SERVICE）", example = "9")
    private Integer inServiceCount;

    @Schema(description = "区间内待接单订单数（PENDING）", example = "6")
    private Integer pendingCount;

    @Schema(description = "履约率 = (COMPLETED + REVIEWED) / (COMPLETED + REVIEWED + CANCELLED)", example = "96.40%")
    private String fulfillmentRate;

    @Schema(description = "平均接单时间（分钟），无已接单订单时为 0", example = "6")
    private Integer avgAcceptMinutes;

    @Schema(description = "投诉率 = 投诉订单数 / 区间订单数", example = "1.20%")
    private String complaintRate;

    @Schema(description = "平均评分（两位小数字符串），无有效评价时为 0.00", example = "4.82")
    private String averageScore;
}

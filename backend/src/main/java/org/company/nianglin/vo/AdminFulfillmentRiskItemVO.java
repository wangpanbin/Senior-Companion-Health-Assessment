package org.company.nianglin.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * 风险中心列表的一行（V3.1 · T6，spec §2.6）。
 *
 * <h3>姓名与订单号为什么脱敏</h3>
 *
 * <p>订单详情的「全名豁免」只适用于<b>已通过归属校验的详情页</b>：
 * 能打开详情的一定是相关方。而列表是可被反复扫读的界面，
 * 一次投屏、一张截图就把多名老人的姓名连同订单号一起带出去了。
 * 本模块沿用全站那把尺子 —— <b>列表脱敏、详情全显</b>。
 * 需要核对全名时，行内「查看详情」打开的是订单详情接口（豁免口径）。</p>
 *
 * @author 银龄伴诊团队
 * @since V3.1
 */
@Data
@Accessors(chain = true)
@Schema(description = "履约风险订单列表项（V3.1；姓名与订单号已脱敏）")
public class AdminFulfillmentRiskItemVO {

    @Schema(description = "订单 ID（跳转详情用，不脱敏）", example = "1001")
    private Long orderId;

    @Schema(description = "订单号（脱敏）", example = "NL20**********0001")
    private String orderNo;

    @Schema(description = "就诊老人姓名（脱敏）", example = "张*海")
    private String elderName;

    @Schema(description = "陪诊医院", example = "市第一人民医院")
    private String hospital;

    @Schema(description = "科室", example = "心内科")
    private String department;

    @Schema(description = "就诊时间", example = "2026-10-18 09:00:00")
    private LocalDateTime visitTime;

    @Schema(description = "风险等级", example = "URGENT")
    private String fulfillmentRisk;

    @Schema(description = "风险中文文案（后端下发，前端不自行判断等级）", example = "履约风险较高")
    private String fulfillmentRiskLabel;

    @Schema(description = "已等待分钟数（由下单时间现算，不落库）", example = "45")
    private Integer waitMinutes;

    @Schema(description = "累计催单次数", example = "2")
    private Integer urgeCount;

    @Schema(description = "是否已有人工介入记录（协调 / 改派过任一条即为 true）", example = "false")
    private Boolean adminCoordinated;

    @Schema(description = "下单时间（列表排序依据之一）", example = "2026-10-17 09:30:00")
    private LocalDateTime createTime;
}

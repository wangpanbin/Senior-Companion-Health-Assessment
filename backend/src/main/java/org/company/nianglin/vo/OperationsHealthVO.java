package org.company.nianglin.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 平台运营驾驶舱的健康度（V3.5 · T3）。
 *
 * <h3>六个并列指标，<b>不给合成分</b></h3>
 *
 * <p>决策 5.2 明确禁止「AI 平台健康指数 96」这类无法解释的数：
 * 六个指标各自的含义都摆在那里，合成一个分之后，
 * 管理员既不知道该盯哪个，也解释不了它为什么是 96。</p>
 *
 * <p>六项统一用字符串渲染 —— 前端把它们并排放在一个块里，
 * 类型一致才不会各写一套格式化逻辑。</p>
 *
 * <p>⚠️ {@code avgAcceptMinutes} 在这里��� {@link String}，
 * 而 {@link OperationsOverviewVO} 的同名字段是 {@link Integer}：
 * 同名不同型是接口设计的坑，两端各按各的 VO 处理，不要写通用取值函数。</p>
 *
 * @author 银龄伴诊团队
 * @since V3.5
 */
@Data
@Accessors(chain = true)
@Schema(description = "平台运营驾驶舱健康度（六个并列指标，不合成总分）")
public class OperationsHealthVO {

    @Schema(description = "履约率 = (COMPLETED + REVIEWED) / (COMPLETED + REVIEWED + CANCELLED)", example = "96.40%")
    private String fulfillmentRate;

    @Schema(description = "接单速度（平均接单时间，分钟）", example = "6")
    private String avgAcceptMinutes;

    @Schema(description = "完成率（按时）= 按时完成单数 / 已完成单数", example = "88.20%")
    private String completionRate;

    @Schema(description = "投诉率 = 投诉订单数 / 区间订单数", example = "1.20%")
    private String complaintRate;

    @Schema(description = "平均评分（两位小数字符串）", example = "4.82")
    private String averageScore;

    @Schema(description = "活跃陪诊员数（区间内接过单的陪诊员数）", example = "24")
    private Integer activeCompanionCount;
}

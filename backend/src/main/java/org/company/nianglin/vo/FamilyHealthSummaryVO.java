package org.company.nianglin.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * 家庭健康协同首页摘要（V3.2 · FR-Summary）。
 *
 * <p>一位老人的一行轻量摘要：今天吃了多少药、近期就医几次、还有几件待办。
 * <b>不含</b>费用、不含催单次数、不含履约风险等级 —— 那些属于订单详情页。</p>
 *
 * @author 银龄伴诊团队
 * @since V3.2
 */
@Data
@Accessors(chain = true)
@Schema(description = "家庭健康协同首页摘要（一位老人一行）")
public class FamilyHealthSummaryVO {

    @Schema(description = "老人档案 ID", example = "301")
    private Long elderId;

    @Schema(description = "老人姓名（脱敏）", example = "张*三")
    private String elderName;

    @Schema(description = "与当前家属的关系", example = "儿子")
    private String relationLabel;

    @Schema(description = "今日用药")
    private MedicationTodayVO todayMedication;

    @Schema(description = "近期已完成陪诊次数", example = "1")
    private Integer recentVisitCount;

    @Schema(description = "待处理事项条数", example = "1")
    private Integer pendingItemCount;

    @Schema(description = "最近一次就诊时间", example = "2026-10-16 09:30:00")
    private LocalDateTime lastActivityAt;
}

package org.company.nianglin.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 用药计划草稿响应（{@code docs/api/05-medication.md} §10，V4 · P1-2）。
 *
 * <p><b>响应刻意不含 dosage</b>：草稿只承载药名 / 次数 / 时间点，
 * 用量字段在前端表单中保持空白且必填，由家属按医嘱自行填写。
 * 这里的 {@code matched=false} 不是错误 —— 药名未命中药品字典时原样返回，
 * 由家属自行核对并重新选择药品。</p>
 *
 * @author 银龄伴诊团队
 * @since V4 · P1-2
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "用药计划草稿")
public class MedicationPlanDraftVO {

    @Schema(description = "订单 ID", example = "1025")
    private Long orderId;

    @Schema(description = "草稿条目")
    private List<Item> items;

    @Schema(description = "草稿判重窗口（分钟）；窗口内重复调用返回 409", example = "30")
    private Integer draftTtlMinutes;

    @Schema(description = "订单服务小结是否缺失；true 时前端应提示家属手动填写药名", example = "false")
    private Boolean summaryMissing;

    /** 兼容旧的三参构造：小结缺失标记为 false，待 Service 显式覆盖 */
    public MedicationPlanDraftVO(Long orderId, List<Item> items, Integer draftTtlMinutes) {
        this(orderId, items, draftTtlMinutes, Boolean.FALSE);
    }

    /**
     * 单条草稿：匹配结果 + 家属填写的次数与时间点。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "草稿条目")
    public static class Item {

        @Schema(description = "匹配到的药品 ID；未匹配为 null", example = "801")
        private Long medicineId;

        @Schema(description = "药品名；未匹配时返回家属填写的原文", example = "苯磺酸氨氯地平片")
        private String medicineName;

        @Schema(description = "是否匹配到药品字典", example = "true")
        private Boolean matched;

        @Schema(description = "每日次数 1–4", example = "1")
        private Integer frequency;

        @Schema(description = "服药时间点 HH:mm", example = "08:00")
        private String timePoint;
    }
}

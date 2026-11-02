package org.company.nianglin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 从订单服务小结转入用药计划草稿的入参（{@code docs/api/05-medication.md} §10，V4 · P1-2）。
 *
 * <p><b>刻意不含 dosage</b>：药名 / 次数 / 时间点由家属自行填写，
 * 本接口只做药品字典比对并回显草稿；用量必须由家属在用药计划页按医嘱自行补齐。
 * 一旦在这里加上 {@code dosage}，系统就具备了「从服务小结生成用药方案」的能力，
 * 直接踩中「不做诊断、不开药方」的合规红线。</p>
 *
 * @author 银龄伴诊团队
 * @since V4 · P1-2
 */
@Data
@Schema(description = "从订单转入用药计划草稿入参")
public class MedicationPlanDraftFromOrderDTO {

    @Schema(description = "老人档案 ID（必须与该订单的就诊老人一致）", example = "425",
            requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "老人档案 ID 不能为空")
    private Long elderId;

    @Schema(description = "草稿条目，1–10 项；不含任何剂量字段", requiredMode = Schema.RequiredMode.REQUIRED)
    @Valid
    @NotEmpty(message = "草稿条目不能为空")
    @Size(max = 10, message = "草稿条目最多 10 项")
    private List<Item> items;

    /**
     * 单条草稿：药品名 + 每日次数 + 一个时间点。
     */
    @Data
    @Schema(description = "草稿条目")
    public static class Item {

        @Schema(description = "药品名（由家属自行填写，会与药品字典做匹配）", example = "苯磺酸氨氯地平片",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "药品名不能为空")
        @Size(max = 100, message = "药品名不能超过 100 个字符")
        private String drugName;

        @Schema(description = "每日次数 1–4", example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "每日次数不能为空")
        @Min(value = 1, message = "每日次数不能小于 1")
        @Max(value = 4, message = "每日次数不能大于 4")
        private Integer frequency;

        @Schema(description = "服药时间点，格式 HH:mm", example = "08:00", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "服药时间点不能为空")
        @Pattern(regexp = "^([01]\\d|2[0-3]):[0-5]\\d$", message = "时间点格式应为 HH:mm")
        private String timePoint;
    }
}

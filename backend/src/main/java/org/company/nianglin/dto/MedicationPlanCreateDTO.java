package org.company.nianglin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
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
 * 新增用药计划入参（{@code docs/api/05-medication.md} §4）。
 *
 * <h3>这个 DTO 刻意不校验「量对不对」</h3>
 *
 * <p>{@code dosage} 只校验长度（≤ 50 字符），不解析「1 片」「半粒」「5ml」
 * 是不是合理剂量，也没有和药品字典的规格做任何比对。
 * <b>那是医嘱的范畴，系统只做记录。</b>
 * 一旦开始判断「这个量是不是太多」，平台就从「记录工具」
 * 变成了「给出用药意见的主体」，直接踩中合规红线。</p>
 *
 * <p>{@code timePoints} 与 {@code frequency} 的一致性（长度必须相等）
 * 放在 Service 里校验，因为它是跨字段约束 —— 注解层面表达不了。</p>
 *
 * @author 银龄伴诊团队
 * @since M6
 */
@Data
@Schema(description = "新增用药计划入参")
public class MedicationPlanCreateDTO {

    @Schema(description = "老人档案 ID（须为当前家属绑定）", example = "301", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "老人档案 ID 不能为空")
    private Long elderId;

    @Schema(description = "药品 ID（须存在于药品字典）", example = "9001", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "药品 ID 不能为空")
    private Long medicineId;

    @Schema(description = "单次用量，由家属按医嘱填写，系统不生成也不校验合理性", example = "1 片",
            requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "单次用量不能为空")
    @Size(max = 50, message = "单次用量不能超过 50 个字符")
    private String dosage;

    @Schema(description = "每日次数 1–4；须与 timePoints 长度一致", example = "1",
            requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "每日次数不能为空")
    @Min(value = 1, message = "每日次数不能小于 1")
    @Max(value = 4, message = "每日次数不能大于 4")
    private Integer frequency;

    @Schema(description = "服药时间点，格式 HH:mm，长度须等于 frequency", example = "[\"08:00\"]",
            requiredMode = Schema.RequiredMode.REQUIRED)
    @NotEmpty(message = "服药时间点不能为空")
    @Size(max = 4, message = "服药时间点最多 4 个")
    private List<@Pattern(regexp = "^([01]\\d|2[0-3]):[0-5]\\d$", message = "时间点格式应为 HH:mm") String> timePoints;

    @Schema(description = "开始日期 yyyy-MM-dd", example = "2026-09-16", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "开始日期不能为空")
    @Pattern(regexp = "^\\d{4}-\\d{2}-\\d{2}$", message = "开始日期格式应为 yyyy-MM-dd")
    private String startDate;

    @Schema(description = "结束日期 yyyy-MM-dd；为空表示长期用药", example = "2026-12-31")
    @Pattern(regexp = "^$|^\\d{4}-\\d{2}-\\d{2}$", message = "结束日期格式应为 yyyy-MM-dd")
    private String endDate;

    @Schema(description = "与饭点关系：BEFORE_MEAL / AFTER_MEAL / ANY", example = "AFTER_MEAL",
            requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "与饭点关系不能为空")
    private String mealRelation;

    @Schema(description = "备注，如「医生让吃两周」", example = "医生让每天早饭吃一片")
    @Size(max = 200, message = "备注不能超过 200 个字符")
    private String remark;
}

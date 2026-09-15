package org.company.nianglin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 修改用药计划入参（{@code docs/api/05-medication.md} §5）。所有字段可选。
 *
 * <h3>{@code elderId} 为什么不在可改字段里</h3>
 *
 * <p>文档写的是「请求体同新增，所有字段可选」，但 {@code elderId} 被刻意排除了。
 * 把一条用药计划从张三移到李四，语义上不是「修改计划」而是
 * <b>「把张三的服药历史挂到李四名下」</b> —— 历史任务里冗余了 {@code elder_id}，
 * 改计划不会改历史，于是同一条计划的两个孩子会各自持有一段时间段的记录。
 * 换个老人服药请新建计划，旧计划停用。</p>
 *
 * <h3>局部更新语义（与 M3 一致）</h3>
 *
 * <p>{@code null} = 不修改；空串 {@code ""} = 清空（{@code endDate} 清成「长期」，
 * {@code remark} 清成 NULL）。这两种意图在 JSON 里长得像，但含义完全不同，
 * 因此不能把它们合并成「值为空就跳过」。</p>
 *
 * <h3>改动只影响未来</h3>
 *
 * <p>改 {@code frequency} / {@code timePoints} 不会回溯修改已经生成的
 * {@code medication_task} 行。理由：那些行是<b>已发生事实的记录</b>，
 * 改它们等于篡改服药史，会让漏服率统计失去意义。</p>
 *
 * @author 银龄伴诊团队
 * @since M6
 */
@Data
@Schema(description = "修改用药计划入参（所有字段可选）")
public class MedicationPlanUpdateDTO {

    @Schema(description = "药品 ID；不传表示不修改", example = "9001")
    private Long medicineId;

    @Schema(description = "单次用量（由家属按医嘱填写）", example = "1 片")
    @Size(max = 50, message = "单次用量不能超过 50 个字符")
    private String dosage;

    @Schema(description = "每日次数 1–4", example = "2")
    @Min(value = 1, message = "每日次数不能小于 1")
    @Max(value = 4, message = "每日次数不能大于 4")
    private Integer frequency;

    @Schema(description = "服药时间点，格式 HH:mm；传了就必须重新给全，长度等于 frequency",
            example = "[\"08:00\",\"20:00\"]")
    @Size(max = 4, message = "服药时间点最多 4 个")
    private List<@Pattern(regexp = "^([01]\\d|2[0-3]):[0-5]\\d$", message = "时间点格式应为 HH:mm") String> timePoints;

    @Schema(description = "开始日期 yyyy-MM-dd", example = "2026-09-16")
    @Pattern(regexp = "^$|^\\d{4}-\\d{2}-\\d{2}$", message = "开始日期格式应为 yyyy-MM-dd")
    private String startDate;

    @Schema(description = "结束日期 yyyy-MM-dd；传空串表示改为长期", example = "2026-12-31")
    @Pattern(regexp = "^$|^\\d{4}-\\d{2}-\\d{2}$", message = "结束日期格式应为 yyyy-MM-dd")
    private String endDate;

    @Schema(description = "与饭点关系：BEFORE_MEAL / AFTER_MEAL / ANY", example = "AFTER_MEAL")
    private String mealRelation;

    @Schema(description = "备注；传空串表示清空", example = "医生让每天早饭吃一片")
    @Size(max = 200, message = "备注不能超过 200 个字符")
    private String remark;
}

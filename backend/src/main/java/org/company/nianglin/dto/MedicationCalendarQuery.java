package org.company.nianglin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 服药日历查询入参（{@code docs/api/05-medication.md} §7）。
 *
 * <p>日期用 {@code String} 而不是 {@code LocalDate}，与 M4 的
 * {@code OrderQuery} 保持同一处理方式：格式错误时能给出
 * 「startDate 日期格式应为 yyyy-MM-dd」这种指名道姓的提示，
 * 而不是 Spring 类型转换失败的通用文案。</p>
 *
 * <p>区间的 31 天上限在 Service 里校验（跨字段约束）：
 * 一天最多 4 个服药时间点，31 天的最坏情况约 124 行任务，
 * 一次性返回给前端渲染日历是合理的；不设上限则可能被拿去
 * 拉一整年的数据，把日历接口变成批量导出的口子。</p>
 *
 * @author 银龄伴诊团队
 * @since M6
 */
@Data
@Schema(description = "服药日历查询入参")
public class MedicationCalendarQuery {

    @Schema(description = "老人档案 ID（必须有权访问）", example = "301", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "老人档案 ID 不能为空")
    private Long elderId;

    @Schema(description = "区间开始日期 yyyy-MM-dd，与结束日期跨度不超过 31 天", example = "2026-09-14",
            requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "开始日期不能为空")
    @Pattern(regexp = "^\\d{4}-\\d{2}-\\d{2}$", message = "开始日期格式应为 yyyy-MM-dd")
    private String startDate;

    @Schema(description = "区间结束日期 yyyy-MM-dd", example = "2026-09-20",
            requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "结束日期不能为空")
    @Pattern(regexp = "^\\d{4}-\\d{2}-\\d{2}$", message = "结束日期格式应为 yyyy-MM-dd")
    private String endDate;
}

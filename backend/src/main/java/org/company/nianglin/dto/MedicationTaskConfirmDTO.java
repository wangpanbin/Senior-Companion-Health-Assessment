package org.company.nianglin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 确认服药入参（{@code docs/api/05-medication.md} §9）。
 *
 * <p>{@code confirmTime} 允许补填历史时间（漏服后补记），但不允许填未来时间 ——
 * 「明天早上 8 点我已经吃过了」显然是误操作，而它会直接污染漏服率统计。
 * 这个校验在 Service 里做，因为「未来」是相对当前时刻的，注解表达不了。</p>
 *
 * <p>{@code confirmBy} 不在入参里：操作人一律取当前登录用户，
 * 让前端传等同于允许伪造操作人，而 {@code confirm_by} 是审计字段。</p>
 *
 * @author 银龄伴诊团队
 * @since M6
 */
@Data
@Schema(description = "确认服药入参")
public class MedicationTaskConfirmDTO {

    @Schema(description = "实际服药时间 yyyy-MM-dd HH:mm:ss；不传取当前时间", example = "2026-09-15 09:05:00")
    @Pattern(regexp = "^$|^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}$",
            message = "实际服药时间格式应为 yyyy-MM-dd HH:mm:ss")
    private String confirmTime;

    @Schema(description = "备注，如「今天外出，晚了 1 小时」", example = "今天外出，晚了 1 小时")
    @Size(max = 200, message = "备注不能超过 200 个字符")
    private String remark;
}

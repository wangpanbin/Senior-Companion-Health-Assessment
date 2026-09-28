package org.company.nianglin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 费用明细录入入参（ADR-0009）。
 *
 * @author 银龄伴诊团队
 * @since M4（收敛迭代 T2.5）
 */
@Data
@Schema(description = "费用明细录入入参")
public class FeeItemCreateDTO {

    @NotBlank(message = "费用类型不能为空")
    @Pattern(regexp = "ADVANCE|SERVICE", message = "费用类型只能是 ADVANCE（代垫）或 SERVICE（服务费）")
    @Schema(description = "费用类型：ADVANCE-代垫 / SERVICE-服务费", example = "ADVANCE")
    private String itemType;

    @NotBlank(message = "项目名不能为空")
    @Size(max = 64, message = "项目名不能超过 64 个字符")
    @Schema(description = "项目名，如「心内科挂号费」", example = "心内科挂号费")
    private String itemName;

    @NotBlank(message = "金额不能为空")
    @Pattern(regexp = "^\\d{1,6}(\\.\\d{1,2})?$", message = "金额须为最多两位小数的数字字符串")
    @Schema(description = "金额（元），字符串两位小数", example = "35.50")
    private String amount;

    @NotNull(message = "费用发生时间不能为空")
    @Schema(description = "费用发生时间", example = "2026-09-28 10:30:00")
    private LocalDateTime occurredAt;
}

package org.company.nianglin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 修改运营参数入参（{@code PUT /api/admin/operation-param/{code}}）。
 *
 * <p>范围校验（min/max）放在 Service：不同参数的合法区间不同，
 * 写死一个全局 {@code @Min/@Max} 只会放行错值或误拦合法值。</p>
 *
 * @author 银龄伴诊团队
 * @since V4 · P2-3
 */
@Data
@Schema(description = "修改运营参数入参")
public class OperationParamUpdateDTO {

    @NotNull(message = "参数值不能为空")
    @Schema(description = "新的整数取值", example = "60")
    private Integer value;

    @Size(max = 200, message = "备注不能超过 200 字")
    @Schema(description = "修改原因 / 备注（可选，写入操作日志）", example = "夜间漏服较多，阈值由 60 调整为 45")
    private String remark;
}

package org.company.nianglin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 干预效果对比入参（V3.6 · T5）。
 *
 * <h3>参数</h3>
 *
 * <ul>
 *   <li>{@code metric}：指标 key，<b>必填</b>；必须在指标计算器注册表里，
 *       否则返回 400（不许猜一个算法算出来）；</li>
 *   <li>{@code from} / {@code to}：<b>后</b>一期（被评估的区间），闭区间；
 *       不传时取最近 30 天，超过 366 天返回 9002；</li>
 *   <li>{@code compareFrom} / {@code compareTo}：<b>前</b>一期（对比区间）；
 *       不传时自动取「与后一期等长、紧邻其前」的区间。</li>
 * </ul>
 *
 * <p>⚠️ 字段名用 {@code from / to} 而不是共享 DTO 的 {@code startDate / endDate}：
 * 这个端点要同时表达<b>两段</b>区间，用「起止」会分不清是哪一段。</p>
 *
 * @author 银龄伴诊团队
 * @since V3.6
 */
@Data
@Schema(description = "干预效果对比入参")
public class OperationsEffectQuery {

    @NotBlank(message = "指标 key 不能为空")
    @Schema(description = "指标 key，必须在指标计算器注册表里登记过",
            example = "operations.fulfillment_rate")
    private String metric;

    @Schema(description = "后一期开始日期（含），yyyy-MM-dd；不传则取最近 30 天",
            example = "2026-10-01")
    private String from;

    @Schema(description = "后一期结束日期（含），yyyy-MM-dd；不传则取今天",
            example = "2026-10-15")
    private String to;

    @Schema(description = "前一期开始日期（含），yyyy-MM-dd；与 compareTo 都不传时自动推导",
            example = "2026-09-16")
    private String compareFrom;

    @Schema(description = "前一期结束日期（含），yyyy-MM-dd；与 compareFrom 都不传时自动推导",
            example = "2026-09-30")
    private String compareTo;
}

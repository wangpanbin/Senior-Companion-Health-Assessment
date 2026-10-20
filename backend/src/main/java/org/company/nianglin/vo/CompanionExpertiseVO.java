package org.company.nianglin.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 擅长服务标签（V3.3 · FR-Profile）。
 *
 * <h3>数据来源是真实业务，不是算法臆造</h3>
 *
 * <p>标签取自 {@code companion_order.department} 维度上「已完成」订单数的 TOP N，
 * 并且<b>样本不足 5 单的科室不展示</b> —— 接过 1 单就自称「擅长普通门诊」，
 * 那不是标签，是噪声。</p>
 *
 * @author 银龄伴诊团队
 * @since V3.3
 */
@Data
@Accessors(chain = true)
@Schema(description = "擅长服务标签")
public class CompanionExpertiseVO {

    @Schema(description = "科室名称", example = "普通门诊")
    private String department;

    @Schema(description = "该科室已完成单数", example = "12")
    private Integer orderCount;
}

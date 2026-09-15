package org.company.nianglin.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

/**
 * 仅返回 ID 的占位出参（{@code {"planId": 7001}}）。
 *
 * <p>对应 {@code docs/api/05-medication.md} §4 的响应体。</p>
 *
 * <p>为什么不直接返回 {@code Result<Long>}：{@code data} 是一个裸数字时，
 * 前端拿到 {@code data} 无法自解释它是谁的 ID，只能靠接口文档记住。
 * 包一层具名字段，代码里 {@code res.data.planId} 一眼就懂，
 * 也方便将来在同一个响应里追加字段而不破坏兼容。</p>
 *
 * @author 银龄伴诊团队
 * @since M6
 */
@Data
@Accessors(chain = true)
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "用药计划 ID")
public class MedicationPlanIdVO {

    @Schema(description = "新建的用药计划 ID", example = "7001")
    private Long planId;
}

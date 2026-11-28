package org.company.nianglin.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * 用药计划创建响应（{@code {"planId": 7001, "effectiveFrom": ...}}）。
 *
 * <p>对应 {@code docs/api/05-medication.md} §4 的响应体。</p>
 *
 * <p>为什么不直接返回 {@code Result<Long>}：{@code data} 是一个裸数字时，
 * 前端拿到 {@code data} 无法自解释它是谁的 ID，只能靠接口文档记住。
 * 包一层具名字段，代码里 {@code res.data.planId} 一眼就懂，
 * 也方便将来在同一个响应里追加字段而不破坏兼容 —— {@code effectiveFrom}
 * 正是这样追加进来的（收口 B · P1-4），老前端多拿一个字段不受影响。</p>
 *
 * @author 银龄伴诊团队
 * @since M6
 */
@Data
@Accessors(chain = true)
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "用药计划创建结果")
public class MedicationPlanIdVO {

    @Schema(description = "新建的用药计划 ID", example = "7001")
    private Long planId;

    @Schema(description = "今天的首个生效时点（收口 B · P1-4）。可能为 null：计划不含今天，"
            + "或当天的时点已全部过完 —— 前端据此提示「今天的 08:00 已过，从 20:00 开始」",
            example = "2026-11-28T20:00:00")
    private LocalDateTime effectiveFrom;
}

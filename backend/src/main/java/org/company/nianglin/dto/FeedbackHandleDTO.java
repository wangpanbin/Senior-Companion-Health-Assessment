package org.company.nianglin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 处理产品反馈入参（{@code docs/api/15-product-feedback.md} §5）。
 *
 * <p>{@code status} 只接受 {@code PROCESSING} / {@code RESOLVED} / {@code REJECTED}。
 * 不能传 {@code PENDING} —— 那等于「把已处理的反馈退回待处理」，
 * 与 {@link org.company.nianglin.constant.FeedbackStatus} 的「只可正向流转」直接冲突。</p>
 *
 * <p>{@code handleResult} <b>每次流转都必填</b>：中间态（处理中）也写，
 * 但只有终态才通过 {@code FeedbackVO} 下发给提交人。中间草稿不外泄 ——
 * 否则用户会以为建议已经有结论了（与 {@code ComplaintVO} 同款处理）。</p>
 *
 * <p>与 {@code ComplaintHandleDTO} 的差别是<b>没有</b> {@code penaltyToTarget}：
 * 产品反馈没有「被投诉人」，不存在计违规这回事。</p>
 *
 * @author 银龄伴诊团队
 * @since V6 · 产品反馈
 */
@Data
@Schema(description = "处理产品反馈入参")
public class FeedbackHandleDTO {

    @Schema(description = "目标状态：PROCESSING / RESOLVED / REJECTED", example = "RESOLVED",
            requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "请指定要流转到的状态")
    private String status;

    @Schema(description = "处理说明，10–500 字符（会经站内信回给提交人，故过敏感词 + 医疗红线两套词表）",
            example = "已排入下一版：订单列表与用药页字号 +2px，老人模式同步放大。",
            requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "请填写处理说明")
    @Size(min = 10, max = 500, message = "处理说明长度应为 10–500 个字符")
    private String handleResult;
}

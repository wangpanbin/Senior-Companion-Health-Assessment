package org.company.nianglin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 提交评价入参（{@code docs/api/06-review-complaint.md} §1）。
 *
 * <h3>{@code familyId} / {@code companionId} 不在入参里</h3>
 *
 * <p>评价人与被评价人都由订单关系推导：评价人 = 订单的 {@code familyId}，
 * 被评价人 = 订单的 {@code companionId}。让前端传这两个字段，
 * 等于允许「给别人的订单写评价」和「给任意陪诊员刷分」两件事同时成立。</p>
 *
 * <h3>{@code content} 的 5 字下限是可写可不写的边界</h3>
 *
 * <p>下限存在不是为了凑字数，而是为了让「不想写但手滑点了一下」的
 * 空评价在提交前就被挡住。评分为主、文字为辅，所以文字允许整体不填。</p>
 *
 * @author 银龄伴诊团队
 * @since M7
 */
@Data
@Schema(description = "提交评价入参")
public class ReviewCreateDTO {

    @Schema(description = "订单 ID（状态须为 COMPLETED）", example = "1001",
            requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "订单 ID 不能为空")
    private Long orderId;

    @Schema(description = "评分 1–5 星", example = "5", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "评分不能为空")
    @Min(value = 1, message = "评分不能低于 1 星")
    @Max(value = 5, message = "评分不能高于 5 星")
    private Integer score;

    @Schema(description = "评价标签，最多 5 个，每个不超过 10 个字符", example = "[\"准时\",\"耐心\",\"沟通清楚\"]")
    @Size(max = 5, message = "评价标签最多 5 个")
    private List<@Size(max = 10, message = "单个标签不能超过 10 个字符") String> tags;

    @Schema(description = "评价文字，5–500 字符；不填表示只打分", example = "小李很耐心，全程陪着老人，取药排队也帮忙。")
    @Size(max = 500, message = "评价文字不能超过 500 个字符")
    private String content;

    @Schema(description = "是否匿名，默认 false", example = "false")
    private Boolean isAnonymous;
}

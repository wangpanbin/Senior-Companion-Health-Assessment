package org.company.nianglin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 家属智能助手 · 问答入参（V3.7 · M14）。
 *
 * <h3>为什么不带 userId</h3>
 *
 * <p>「谁在问」一律取自令牌，绝不从参数收 —— 否则等于允许家属以别人的名义提问，
 * 而提问会带出该家属的老人列表与订单摘要。
 * 同一个纪律见 {@code OrderCreateDTO} 的 {@code familyId} 说明。</p>
 *
 * <h3>为什么没有 conversationId</h3>
 *
 * <p>本助手<b>无状态</b>：历史由前端 {@code sessionStorage} 保存、随请求带上，
 * 后端不落库、不建会话表（ADR-0020 决策 6）。
 * 所以也不需要会话 ID —— 有会话 ID 就意味着后端有状态。</p>
 *
 * <h3>为什么 {@code history} 的上限是 12 条而不是 6 条</h3>
 *
 * <p><b>一条 history 项 = 半轮对话</b>（user 一条 + assistant 一条）。
 * 「6 轮」= 12 条消息。如果这里按 6 <b>条</b> 卡，前端每轮发 2 条，
 * 问到第 5 轮就会发 8 条 → 被 {@code @Size} 拦成 400 ——
 * <b>用得越久越坏</b>，而这种故障在只测 1–2 轮时根本看不出来。
 * 所以上限按<b>消息条数</b>写（12），「6 轮」这个语义记在消息里。</p>
 *
 * @author 银龄伴诊团队
 * @since V3.7
 */
@Data
@Schema(description = "智能助手问答入参")
public class AssistantChatDTO {

    @Schema(description = "家属的问题", example = "陪诊费多少钱")
    @NotBlank(message = "请输入你的问题")
    @Size(max = 500, message = "问题不能超过 500 个字符")
    private String question;

    @Schema(description = "历史轮次（前端 sessionStorage 维护，后端不落库）")
    @Valid
    @Size(max = 12, message = "历史不能超过 6 轮（12 条消息）")
    private List<AssistantHistoryTurn> history = new ArrayList<>();

    @Schema(description = "当前订单 ID；给了则装配该单的上下文与候选陪诊员", example = "1001")
    private Long orderId;

    @Schema(description = "就诊老人档案 ID；给了则校验是否为本家属绑定的老人", example = "401")
    private Long elderId;
}

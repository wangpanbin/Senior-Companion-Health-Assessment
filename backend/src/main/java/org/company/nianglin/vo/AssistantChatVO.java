package org.company.nianglin.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.ArrayList;
import java.util.List;

/**
 * 家属智能助手 · 问答出参（V3.7 · M14）。
 *
 * <h3>为什么 {@code sources} 是一等字段</h3>
 *
 * <p>助手的每个回答都必须能回答「这个结论的依据在哪」。
 * 没有 {@code sources} 的回答在评审现场是站不住的 —— 它看起来像个答案，
 * 但没人能验证。这与 ADR-0016「不允许出现没有理由的分数」是同一条纪律，
 * 只是对象从「分数」换成了「回答」。</p>
 *
 * @author 银龄伴诊团队
 * @since V3.7
 */
@Data
@Accessors(chain = true)
@Schema(description = "智能助手问答结果")
public class AssistantChatVO {

    @Schema(description = "自然语言回答（已过合规输出护栏）",
            example = "工作日白天 128.00 元，夜间或周末 158.00 元。下单时不用自己填费用。")
    private String answer;

    @Schema(description = "命中的意图", example = "FAQ_PLATFORM")
    private String intent;

    @Schema(description = "结构化动作（如回填下单草稿）")
    private List<AssistantActionVO> actions = new ArrayList<>();

    @Schema(description = "答案的依据引用", example = "[\"docs/api/03-order.md §1\"]")
    private List<String> sources = new ArrayList<>();

    @Schema(description = "是否走了降级路径（未接入大模型，直接给标准答案原文）", example = "false")
    private Boolean degraded = Boolean.FALSE;
}

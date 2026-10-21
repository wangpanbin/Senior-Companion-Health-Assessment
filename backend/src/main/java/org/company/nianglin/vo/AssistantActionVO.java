package org.company.nianglin.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 助手的结构化动作（V3.7 · M14）。
 *
 * <h3>为什么动作是「结构化」而不是「让模型自己说」</h3>
 *
 * <p>如果把「帮我把下单信息填好」交给模型用自然语言表达，前端就得去解析那句话，
 * 那条正则迟早会漏。现在它是明确的类型 + 明确的字段，
 * 前端只按类型分发，模型完全不参与这件事。</p>
 *
 * <h3>助手<b>永不</b>自动下单</h3>
 *
 * <p>{@code FILL_ORDER_DRAFT} 只是把值填进前端草稿，用户点击「去确认下单」
 * 之后才走既有的下单流程。若助手直接建单，PENDING 订单会出现在陪诊员大厅里，
 * 污染真实供给（ADR-0020 决策 5）。</p>
 *
 * @author 银龄伴诊团队
 * @since V3.7
 */
@Data
@Accessors(chain = true)
@Schema(description = "助手结构化动作")
public class AssistantActionVO {

    @Schema(description = "动作类型", example = "FILL_ORDER_DRAFT")
    private String type;

    @Schema(description = "动作参数（只含助手确实抽取到的字段）")
    private Map<String, Object> payload = new LinkedHashMap<>();
}

package org.company.nianglin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 助手对话的一轮历史（V3.7 · M14）。
 *
 * <p>只承载「说过什么」，不承载任何业务标识 —— 没有订单 ID、没有老人 ID。
 * 这是隐私最小化的一个小但具体的设计：历史在浏览器里，
 * 后端只是这一次的透传者。</p>
 *
 * @author 银龄伴诊团队
 * @since V3.7
 */
@Data
@Schema(description = "助手对话的一轮历史")
public class AssistantHistoryTurn {

    @Schema(description = "说话方：user / assistant", example = "user")
    @NotBlank(message = "说话方不能为空")
    @Size(max = 20, message = "说话方取值过长")
    private String role;

    @Schema(description = "该轮内容", example = "陪诊费多少钱")
    @NotBlank(message = "内容不能为空")
    @Size(max = 1000, message = "单轮内容不能超过 1000 个字符")
    private String content;
}

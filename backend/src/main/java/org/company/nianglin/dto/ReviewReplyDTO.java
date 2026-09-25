package org.company.nianglin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 陪诊员回复评价入参（E4 评价公信力闭环）。
 *
 * <p>对应 {@code docs/api/06-review-complaint.md} §X「回复评价」。</p>
 *
 * <h3>为什么是 5–200 字符（不是 5–500）</h3>
 *
 * <p>与评价文字 5–500 字符的下限一致（5 字以下的多半是误触），
 * 上限比评价文字更短（200 vs 500）—— 回复是「补充事实」，不是「写小作文」。
 * 评价本身的字上限在 M7 已经定，回复比它再宽会让管理员巡查时看不完。</p>
 *
 * @author 银龄伴诊团队
 * @since E4
 */
@Data
@Schema(description = "陪诊员回复评价入参")
public class ReviewReplyDTO {

    @Schema(description = "回复内容，5–200 字符", example = "当日 08:20 已到院打卡（可查证），迟到或因老人下楼较慢，全程服务节点均按时完成。",
            requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "回复内容不能为空")
    @Size(min = 5, max = 200, message = "回复内容长度应为 5–200 个字符")
    private String content;
}
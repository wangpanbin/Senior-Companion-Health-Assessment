package org.company.nianglin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 管理员评价有效性裁定入参（E4 评价公信力闭环）。
 *
 * <p>对应 {@code docs/api/06-review-complaint.md} §X「评价裁定」。</p>
 *
 * <h3>为什么用 {@code @AssertTrue} 校验「仅接受 isValid=false」</h3>
 *
 * <p>本期只做「有效 → 无效」的单向裁定（PRD §FR-03 规则 2）。
 * 把「不接受 true」写在 {@code @AssertTrue} 上有两个好处：</p>
 * <ol>
 *   <li>校验先于 Service：前端误传 {@code isValid=true} 直接 400，不必走完一遍业务再拒绝</li>
 *   <li>语义比「@Size(min=0, max=0)」更清楚 —— 后者会被读成「长度约束」，</li>
 *   <li>错误码翻译成 400（PARAM_ERROR），与本类 reason 长度校验的失败码一致</li>
 * </ol>
 *
 * <h3>理由长度 10–200</h3>
 *
 * <p>10 字以下写不出可被核对的裁定理由（PRD §FR-03 验收标准明确「≥ 10 字」），
 * 200 字上限防止管理员写长篇大论淹没了通知摘要（站内信展示前 30 字）。</p>
 *
 * @author 银龄伴诊团队
 * @since E4
 */
@Data
@Schema(description = "管理员裁定评价有效性入参")
public class ReviewRulingDTO {

    @Schema(description = "裁定结论，本期仅接受 false（恢复有效不在范围）",
            example = "false", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "isValid 不能为空")
    private Boolean isValid;

    @Schema(description = "裁定理由，10–200 字符，对双方可见",
            example = "家属描述与打卡记录明显不符，证据充分，裁定为无效评价。",
            requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "reason 不能为空")
    @Size(min = 10, max = 200, message = "裁定理由长度应为 10–200 个字符")
    private String reason;

    /**
     * 「仅接受 isValid=false」的硬性约束。
     *
     * <p>用方法名而非字段名做校验点 —— Bean Validation 默认会把它当作派生约束，
     * 消息挂在方法名 {@code message} 上不会冲突。</p>
     */
    @AssertTrue(message = "本期仅支持裁定为无效，恢复有效请走线下流程")
    public boolean isOnlyRejecting() {
        // null 由 @NotNull 把关；这里只关心「true 是否被拒绝」
        return isValid == null || !isValid;
    }
}
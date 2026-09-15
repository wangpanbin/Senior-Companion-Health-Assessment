package org.company.nianglin.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 仅返回老人档案 ID 的结果。
 *
 * <p>用于「新增老人档案」（{@code docs/api/02-elder-family.md} §7）与
 * 「绑定老人账号」（§11）—— 前端拿到 ID 后即可跳转到档案详情页，
 * 不需要后端再回一份完整 VO（那会把明文姓名、地址多送出去一遍）。</p>
 *
 * @author 银龄伴诊团队
 * @since M3
 */
@Data
@Accessors(chain = true)
@Schema(description = "老人档案 ID 结果")
public class ElderIdVO {

    @Schema(description = "老人档案 ID", example = "401")
    private Long elderId;

    public static ElderIdVO of(Long elderId) {
        return new ElderIdVO().setElderId(elderId);
    }
}

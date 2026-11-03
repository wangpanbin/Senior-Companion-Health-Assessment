package org.company.nianglin.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 体征撤回结果（V4 · P2-2）。
 *
 * <p>撤回是逻辑删除：记录行保留，{@code deleted=1}，时间线与趋势不再返回。</p>
 *
 * @author 银龄伴诊团队
 * @since V4 · P2-2
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "体征撤回结果")
public class VitalWithdrawVO {

    @Schema(description = "记录 ID", example = "123")
    private Long recordId;

    @Schema(description = "是否已撤回（恒为 true）", example = "true")
    private Boolean deleted;

    /** 撤回成功结果 */
    public static VitalWithdrawVO withdrawn(Long recordId) {
        return new VitalWithdrawVO(recordId, Boolean.TRUE);
    }
}

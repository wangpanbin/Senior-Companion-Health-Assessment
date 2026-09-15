package org.company.nianglin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 解封用户入参（{@code docs/api/08-admin.md} §6）。
 *
 * <p>解封不强制填原因（文档里 {@code remark} 也是可选），
 * 但日志里会记录一条 {@code ENABLE_USER}。这与封禁不对称是刻意的：
 * 封禁是「限制他人权利」，需要理由；解封是「恢复原状」，多说一句是补充信息。</p>
 *
 * @author 银龄伴诊团队
 * @since M9
 */
@Data
@Schema(description = "解封用户入参")
public class UserEnableDTO {

    @Schema(description = "备注，≤ 200 字符", example = "用户提交了情况说明，申诉通过")
    @Size(max = 200, message = "备注不能超过 200 个字符")
    private String remark;
}

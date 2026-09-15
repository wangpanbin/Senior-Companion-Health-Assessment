package org.company.nianglin.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 用户封禁 / 解封结果（{@code docs/api/08-admin.md} §5 / §6）。
 *
 * @author 银龄伴诊团队
 * @since M9
 */
@Data
@Accessors(chain = true)
@Schema(description = "用户状态变更结果")
public class UserStatusResultVO {

    @Schema(description = "用户 ID", example = "10099")
    private Long userId;

    @Schema(description = "变更后状态：NORMAL / DISABLED", example = "DISABLED")
    private String status;

    @Schema(description = "变更后状态中文", example = "已封禁")
    private String statusLabel;

    public static UserStatusResultVO of(Long userId, String status) {
        return new UserStatusResultVO()
                .setUserId(userId)
                .setStatus(status)
                .setStatusLabel("DISABLED".equals(status) ? "已封禁"
                        : "NORMAL".equals(status) ? "正常" : status);
    }
}

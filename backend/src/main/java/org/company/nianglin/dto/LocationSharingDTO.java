package org.company.nianglin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 位置共享开关入参（{@code PUT /api/execution/{orderId}/location-sharing}，ADR-0022）。
 *
 * <p>对应 {@code docs/api/04-companion-execution.md} §9。</p>
 *
 * <p>{@code enabled} 用包装类型 {@link Boolean} 而不是 {@code boolean}：
 * 前者能把「没传」与「传了 false」区分开。若用基本类型，漏传会被静默当成
 * {@code false} —— 一次忘记传参就悄悄关掉了位置共享，家属端从此看不到人，
 * 而调用方以为自己只是「刷新了一下状态」。</p>
 *
 * @author 银龄伴诊团队
 * @since ADR-0022
 */
@Data
@Schema(description = "位置共享开关入参")
public class LocationSharingDTO {

    @Schema(description = "true 恢复共享，false 暂停共享", example = "false",
            requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "请指定是否共享位置")
    private Boolean enabled;
}

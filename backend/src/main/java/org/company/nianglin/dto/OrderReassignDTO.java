package org.company.nianglin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 管理员改派陪诊员入参（V3.1 · T7，spec §2.7）。
 *
 * @author 银龄伴诊团队
 * @since V3.1
 */
@Data
@Accessors(chain = true)
@Schema(description = "管理员改派陪诊员入参（V3.1）")
public class OrderReassignDTO {

    @Schema(description = "目标陪诊员的用户 ID", example = "302")
    @NotNull(message = "目标陪诊员不能为空")
    private Long companionId;

    /**
     * 改派理由。
     *
     * <p>10–200 字且过合规词表：这条理由会同时写进操作审计与三方站内信，
     * 「一句话改派」在事后取证时等于没有理由。</p>
     */
    @Schema(description = "改派理由，10–200 字，写入审计与三方通知", example = "原陪诊员临时有事无法前往，已协调另一位陪诊员接单")
    @NotBlank(message = "改派理由不能为空")
    @Size(min = 10, max = 200, message = "改派理由需 10-200 字")
    private String reason;
}

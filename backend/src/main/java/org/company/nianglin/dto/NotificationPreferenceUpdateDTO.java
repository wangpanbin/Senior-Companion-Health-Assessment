package org.company.nianglin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 通知偏好更新入参（V4 · P1-3）。
 *
 * <p>只暴露两个开关：漏服站外短信、静默时段。两者都必填 ——
 * 前端开关自然总会给出布尔值，若允许 null，后端就得猜「没传是保持原值还是关闭」，
 * 这个歧义在提醒配置上代价太高。</p>
 *
 * @author 银龄伴诊团队
 * @since V4 · P1-3
 */
@Data
@Schema(description = "通知偏好更新入参")
public class NotificationPreferenceUpdateDTO {

    @NotNull
    @Schema(description = "是否接收漏服站外短信：true-开启 false-关闭（关闭后站内信仍保留）",
            example = "true", requiredMode = Schema.RequiredMode.REQUIRED)
    private Boolean smsMissedEnabled;

    @NotNull
    @Schema(description = "是否启用静默时段（22:00–08:00 不发送短信，顺延到次日 08:00）",
            example = "true", requiredMode = Schema.RequiredMode.REQUIRED)
    private Boolean quietHoursEnabled;
}

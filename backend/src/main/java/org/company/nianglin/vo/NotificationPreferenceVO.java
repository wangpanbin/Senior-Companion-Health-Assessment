package org.company.nianglin.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 通知偏好出参（V4 · P1-3）。
 *
 * <p>与入参同构：GET 与 PUT 都返回这两个布尔值，前端保存成功后直接用返回值刷新本地状态，
 * 不用再发一次 GET。</p>
 *
 * @author 银龄伴诊团队
 * @since V4 · P1-3
 */
@Data
@Schema(description = "通知偏好")
public class NotificationPreferenceVO {

    @Schema(description = "是否接收漏服站外短信；关闭后仍保留站内信", example = "true")
    private Boolean smsMissedEnabled;

    @Schema(description = "是否启用静默时段（22:00–08:00 不发送，顺延到次日 08:00）", example = "true")
    private Boolean quietHoursEnabled;
}

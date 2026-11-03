package org.company.nianglin.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.company.nianglin.common.BaseEntity;

/**
 * NotificationPreference —— 对应表 {@code notification_preference}。
 *
 * <p>通知偏好表（V4 P1-3 站外短信开关与静默时段）</p>
 *
 * <p>主键与审计字段（id / createTime / updateTime / deleted）继承自 {@link BaseEntity}。</p>
 *
 * <p>⚠️ 本类为持久化实体，禁止直接作为接口返回值；对外统一转 VO，
 * 避免密码、身份证号、完整手机号等敏感字段外泄。</p>
 *
 * @since M1
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("notification_preference")
public class NotificationPreference extends BaseEntity {

    /**
     * 用户 ID（当前仅家属使用）
     */
    @Schema(description = "用户 ID（当前仅家属使用）")
    private Long userId;

    /**
     * 漏服站外短信开关：1-开 0-关（关闭后仍保留站内信）
     */
    @Schema(description = "漏服站外短信开关：1-开 0-关（关闭后仍保留站内信）")
    private Integer smsMissedEnabled;

    /**
     * 静默时段开关：1-开 0-关（开启后 22:00–08:00 不发送，顺延到 08:00）
     */
    @Schema(description = "静默时段开关：1-开 0-关（开启后 22:00–08:00 不发送，顺延到 08:00）")
    private Integer quietHoursEnabled;

}

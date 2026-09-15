package org.company.nianglin.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.company.nianglin.common.BaseEntity;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * MedicationTask —— 对应表 {@code medication_task}。
 *
 * <p>每日服药任务表（定时任务生成，唯一索引保证幂等）</p>
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
@TableName("medication_task")
public class MedicationTask extends BaseEntity {

    /**
     * 所属用药计划 ID
     */
    @Schema(description = "所属用药计划 ID")
    private Long planId;

    /**
     * 老人档案 ID（冗余，避免查任务时再关联计划表）
     */
    @Schema(description = "老人档案 ID（冗余，避免查任务时再关联计划表）")
    private Long elderId;

    /**
     * 药品 ID（冗余）
     */
    @Schema(description = "药品 ID（冗余）")
    private Long medicineId;

    /**
     * 药品名快照
     */
    @Schema(description = "药品名快照")
    private String medicineName;

    /**
     * 单次用量快照
     */
    @Schema(description = "单次用量快照")
    private String dosage;

    /**
     * 饭点关系快照（历史任务不受计划修改影响）
     */
    @Schema(description = "饭点关系快照（历史任务不受计划修改影响）")
    private String mealRelation;

    /**
     * 计划日期（按天聚合用）
     */
    @Schema(description = "计划日期（按天聚合用）")
    private LocalDate planDate;

    /**
     * 计划服药时间（精确到分钟）
     */
    @Schema(description = "计划服药时间（精确到分钟）")
    private LocalDateTime planTime;

    /**
     * 任务状态：PENDING-待服用 / TAKEN-已服用 / MISSED-漏服
     */
    @Schema(description = "任务状态：PENDING-待服用 / TAKEN-已服用 / MISSED-漏服")
    private String status;

    /**
     * 是否曾判定漏服后补记：0-否 1-是（统计漏服率用）
     */
    @Schema(description = "是否曾判定漏服后补记：0-否 1-是（统计漏服率用）")
    private Integer wasMissed;

    /**
     * 实际确认/服药时间
     */
    @Schema(description = "实际确认/服药时间")
    private LocalDateTime confirmTime;

    /**
     * 确认人用户 ID
     */
    @Schema(description = "确认人用户 ID")
    private Long confirmBy;

    /**
     * 确认备注（如「今天外出，晚了 1 小时」）
     */
    @Schema(description = "确认备注（如「今天外出，晚了 1 小时」）")
    private String confirmRemark;

    /**
     * 漏服提醒是否已推送：0-否 1-是（防重复推送）
     */
    @Schema(description = "漏服提醒是否已推送：0-否 1-是（防重复推送）")
    private Integer notifySent;

    /**
     * 提醒推送时间
     */
    @Schema(description = "提醒推送时间")
    private LocalDateTime notifyTime;

}

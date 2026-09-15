package org.company.nianglin.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;
import org.company.nianglin.constant.MealRelation;
import org.company.nianglin.constant.MedicationTaskStatus;
import org.company.nianglin.entity.MedicationTask;

import java.time.LocalDateTime;

/**
 * 服药任务（对外唯一出口）。
 *
 * <p>对应 {@code docs/api/05-medication.md} §7（日历中的任务）/ §8（今日待服）。</p>
 *
 * <h3>时间字段为什么不自己格式化成 String</h3>
 *
 * <p>全局已经由 {@code config/JacksonConfig} 把 {@code LocalDateTime}
 * 统一序列化成 {@code yyyy-MM-dd HH:mm:ss}。在这里再手工
 * {@code format()} 一遍，看起来结果一样，实际埋了两颗雷：
 * 一是将来统一换格式（比如加毫秒或换时区）时会漏掉这个类；
 * 二是格式化会把 {@code null} 变成字符串 {@code "null"} 或直接 NPE。
 * <b>保持类型，格式交给一处管。</b></p>
 *
 * <h3>要不要返回 {@code wasMissed}</h3>
 *
 * <p>返回。前端据此显示「补记」标签，而漏服率统计也必须能解释
 * 「为什么这条显示已服、却算进漏服」。把统计口径藏在服务端，
 * 会让家属看到的数字与页面逐条记录对不上而产生疑问。</p>
 *
 * @author 银龄伴诊团队
 * @since M6
 */
@Data
@Accessors(chain = true)
@Schema(description = "服药任务")
public class MedicationTaskVO {

    @Schema(description = "任务 ID", example = "60001")
    private Long id;

    @Schema(description = "所属用药计划 ID", example = "7001")
    private Long planId;

    @Schema(description = "药品名（任务生成时的快照）", example = "苯磺酸氨氯地平片")
    private String medicineName;

    @Schema(description = "单次用量（快照）", example = "1 片")
    private String dosage;

    @Schema(description = "计划服药时间", example = "2026-09-15 08:00:00")
    private LocalDateTime planTime;

    @Schema(description = "任务状态：PENDING / TAKEN / MISSED", example = "TAKEN")
    private String status;

    @Schema(description = "任务状态中文名", example = "已服")
    private String statusLabel;

    @Schema(description = "与饭点关系：BEFORE_MEAL / AFTER_MEAL / ANY", example = "AFTER_MEAL")
    private String mealRelation;

    @Schema(description = "与饭点关系中文名", example = "饭后")
    private String mealRelationLabel;

    @Schema(description = "实际确认时间；未确认时为 null", example = "2026-09-15 08:12:00")
    private LocalDateTime confirmTime;

    @Schema(description = "确认人姓名（脱敏）", example = "张*")
    private String confirmByName;

    @Schema(description = "确认备注", example = "今天外出，晚了 1 小时")
    private String confirmRemark;

    @Schema(description = "是否曾判定漏服后补记；补记的记录在漏服率统计中仍计为漏服", example = "false")
    private Boolean wasMissed;

    /**
     * 装配。
     *
     * @param entity        任务实体
     * @param confirmByName 确认人姓名（已解密），本方法内就地脱敏；无确认人传 {@code null}
     */
    public static MedicationTaskVO of(MedicationTask entity, String confirmByName) {
        if (entity == null) {
            return null;
        }
        return new MedicationTaskVO()
                .setId(entity.getId())
                .setPlanId(entity.getPlanId())
                .setMedicineName(entity.getMedicineName())
                .setDosage(entity.getDosage())
                .setPlanTime(entity.getPlanTime())
                .setStatus(entity.getStatus())
                .setStatusLabel(MedicationTaskStatus.labelOf(entity.getStatus()))
                .setMealRelation(entity.getMealRelation())
                .setMealRelationLabel(MealRelation.labelOf(entity.getMealRelation()))
                .setConfirmTime(entity.getConfirmTime())
                .setConfirmByName(org.company.nianglin.util.MaskUtil.name(confirmByName))
                .setConfirmRemark(entity.getConfirmRemark())
                .setWasMissed(entity.getWasMissed() != null && entity.getWasMissed() == 1);
    }
}

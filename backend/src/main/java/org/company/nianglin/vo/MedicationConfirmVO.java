package org.company.nianglin.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;
import org.company.nianglin.constant.MedicationTaskStatus;
import org.company.nianglin.entity.MedicationTask;
import org.company.nianglin.util.MaskUtil;

import java.time.LocalDateTime;

/**
 * 确认服药的返回（{@code docs/api/05-medication.md} §9）。
 *
 * <p>确认成功后只回这一条任务的最终状态，<b>不回整个日历</b>。
 * 日历数据量随区间长度增长，而这一步操作只影响一行；
 * 回全量会让「点一下确认」的耗时取决于用户之前翻到多长的区间。</p>
 *
 * @author 银龄伴诊团队
 * @since M6
 */
@Data
@Accessors(chain = true)
@Schema(description = "确认服药结果")
public class MedicationConfirmVO {

    @Schema(description = "任务 ID", example = "60003")
    private Long taskId;

    @Schema(description = "任务状态：TAKEN", example = "TAKEN")
    private String status;

    @Schema(description = "任务状态中文名", example = "已服")
    private String statusLabel;

    @Schema(description = "实际确认时间", example = "2026-09-15 09:05:00")
    private LocalDateTime confirmTime;

    @Schema(description = "确认人姓名（脱敏）", example = "张*")
    private String confirmByName;

    @Schema(description = "是否为漏服后补记；补记在漏服率统计中仍计为漏服", example = "true")
    private Boolean wasMissed;

    @Schema(description = "确认备注", example = "今天外出，晚了 1 小时")
    private String confirmRemark;

    /**
     * 装配。
     *
     * @param entity        任务实体（已更新后的状态）
     * @param confirmByName 确认人姓名（已解密），本方法内就地脱敏
     */
    public static MedicationConfirmVO of(MedicationTask entity, String confirmByName) {
        if (entity == null) {
            return null;
        }
        return new MedicationConfirmVO()
                .setTaskId(entity.getId())
                .setStatus(entity.getStatus())
                .setStatusLabel(MedicationTaskStatus.labelOf(entity.getStatus()))
                .setConfirmTime(entity.getConfirmTime())
                .setConfirmByName(MaskUtil.name(confirmByName))
                .setWasMissed(entity.getWasMissed() != null && entity.getWasMissed() == 1)
                .setConfirmRemark(entity.getConfirmRemark());
    }
}

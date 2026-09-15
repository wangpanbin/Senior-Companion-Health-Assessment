package org.company.nianglin.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;
import org.company.nianglin.constant.MealRelation;
import org.company.nianglin.constant.MedicationPlanStatus;
import org.company.nianglin.entity.MedicationPlan;
import org.company.nianglin.util.MaskUtil;

import java.time.LocalDate;
import java.util.List;

/**
 * 用药计划（对外唯一出口）。
 *
 * <p>对应 {@code docs/api/05-medication.md} §3。</p>
 *
 * <h3>{@code timePoints} 的序列化方向</h3>
 *
 * <p>数据库里存的是 JSON 字符串（{@code ["08:00","12:00"]}），VO 里是
 * {@code List<String>}。转换只在 Service 一处完成：一旦允许 Service
 * 直接把 JSON 串塞进 VO，前端就会收到一个「长得像数组的字符串」，
 * 而它 {@code JSON.parse} 一下也能用 —— 于是这个错误会一直潜伏到
 * 有人用 {@code .length} 取到字符数的那一刻。</p>
 *
 * <h3>{@code elderName} 一律脱敏</h3>
 *
 * <p>用药计划没有「详情页要全名」的场景（它不是订单，不需要核对身份），
 * 所以只有一种口径：脱敏。少一个口径就少一处遗漏的机会。</p>
 *
 * @author 银龄伴诊团队
 * @since M6
 */
@Data
@Accessors(chain = true)
@Schema(description = "用药计划")
public class MedicationPlanVO {

    @Schema(description = "计划 ID", example = "7001")
    private Long id;

    @Schema(description = "老人档案 ID", example = "301")
    private Long elderId;

    @Schema(description = "老人姓名（脱敏）", example = "张*三")
    private String elderName;

    @Schema(description = "药品 ID", example = "9001")
    private Long medicineId;

    @Schema(description = "药品名（计划创建时的快照）", example = "苯磺酸氨氯地平片")
    private String medicineName;

    @Schema(description = "单次用量（家属按医嘱填写）", example = "1 片")
    private String dosage;

    @Schema(description = "每日次数", example = "1")
    private Integer frequency;

    @Schema(description = "服药时间点", example = "[\"08:00\"]")
    private List<String> timePoints;

    @Schema(description = "开始日期", example = "2026-09-01")
    private LocalDate startDate;

    @Schema(description = "结束日期；为空表示长期", example = "2026-12-31")
    private LocalDate endDate;

    @Schema(description = "与饭点关系：BEFORE_MEAL / AFTER_MEAL / ANY", example = "AFTER_MEAL")
    private String mealRelation;

    @Schema(description = "与饭点关系中文名", example = "饭后")
    private String mealRelationLabel;

    @Schema(description = "计划状态：ACTIVE / DISABLED", example = "ACTIVE")
    private String status;

    @Schema(description = "计划状态中文名", example = "进行中")
    private String statusLabel;

    @Schema(description = "备注", example = "医生让每天早饭吃一片")
    private String remark;

    /**
     * 装配。
     *
     * @param entity       计划实体
     * @param elderName    老人姓名（已解密），本方法内就地脱敏
     * @param timePoints   已解析的时间点列表；传 {@code null} 表示解析失败，按空列表返回
     */
    public static MedicationPlanVO of(MedicationPlan entity, String elderName, List<String> timePoints) {
        if (entity == null) {
            return null;
        }
        return new MedicationPlanVO()
                .setId(entity.getId())
                .setElderId(entity.getElderId())
                .setElderName(MaskUtil.name(elderName))
                .setMedicineId(entity.getMedicineId())
                .setMedicineName(entity.getMedicineName())
                .setDosage(entity.getDosage())
                .setFrequency(entity.getFrequency())
                .setTimePoints(timePoints == null ? List.of() : timePoints)
                .setStartDate(entity.getStartDate())
                .setEndDate(entity.getEndDate())
                .setMealRelation(entity.getMealRelation())
                .setMealRelationLabel(MealRelation.labelOf(entity.getMealRelation()))
                .setStatus(entity.getStatus())
                .setStatusLabel(MedicationPlanStatus.labelOf(entity.getStatus()))
                .setRemark(entity.getRemark());
    }
}

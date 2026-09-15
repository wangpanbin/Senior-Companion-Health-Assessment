package org.company.nianglin.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.company.nianglin.common.BaseEntity;
import java.time.LocalDate;

/**
 * MedicationPlan —— 对应表 {@code medication_plan}。
 *
 * <p>用药计划表</p>
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
@TableName("medication_plan")
public class MedicationPlan extends BaseEntity {

    /**
     * 老人档案 ID
     */
    @Schema(description = "老人档案 ID")
    private Long elderId;

    /**
     * 药品 ID
     */
    @Schema(description = "药品 ID")
    private Long medicineId;

    /**
     * 药品通用名快照（药品字典变更后历史计划仍可读）
     */
    @Schema(description = "药品通用名快照（药品字典变更后历史计划仍可读）")
    private String medicineName;

    /**
     * 单次用量，如「1 片」（**家属按医嘱填写**）
     */
    @Schema(description = "单次用量，如「1 片」（**家属按医嘱填写**）")
    private String dosage;

    /**
     * 每日次数 1-4
     */
    @Schema(description = "每日次数 1-4")
    private Integer frequency;

    /**
     * 服药时间点 JSON 数组，如 ["08:00","12:00","18:00"]，长度须等于 frequency
     */
    @Schema(description = "服药时间点 JSON 数组，如 ['08:00','12:00','18:00']，长度须等于 frequency")
    private String timePoints;

    /**
     * 开始日期
     */
    @Schema(description = "开始日期")
    private LocalDate startDate;

    /**
     * 结束日期，NULL 表示长期用药
     */
    @Schema(description = "结束日期，NULL 表示长期用药")
    private LocalDate endDate;

    /**
     * 与饭点关系：BEFORE_MEAL-饭前 / AFTER_MEAL-饭后 / ANY-不限
     */
    @Schema(description = "与饭点关系：BEFORE_MEAL-饭前 / AFTER_MEAL-饭后 / ANY-不限")
    private String mealRelation;

    /**
     * 计划状态：ACTIVE-进行中 / DISABLED-已停用（**不物理删除，保留服药历史**）
     */
    @Schema(description = "计划状态：ACTIVE-进行中 / DISABLED-已停用（**不物理删除，保留服药历史**）")
    private String status;

    /**
     * 备注（如「医生让吃两周」）
     */
    @Schema(description = "备注（如「医生让吃两周」）")
    private String remark;

    /**
     * 创建人（家属）用户 ID
     */
    @Schema(description = "创建人（家属）用户 ID")
    private Long createdBy;

}

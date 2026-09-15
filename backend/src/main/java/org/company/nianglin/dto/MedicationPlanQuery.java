package org.company.nianglin.dto;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.company.nianglin.common.PageQuery;
import org.company.nianglin.entity.MedicationPlan;

/**
 * 用药计划列表查询入参（{@code docs/api/05-medication.md} §3）。
 *
 * <p>{@code elderId} 必填且必须做归属校验：用药计划的名字、剂量、
 * 时间点放出去就等同于「谁在吃哪种药、什么时候吃」，
 * 这比订单信息敏感得多 —— 订单只暴露「去过医院」，计划暴露的是病情线索。</p>
 *
 * @author 银龄伴诊团队
 * @since M6
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "用药计划列表查询入参")
public class MedicationPlanQuery extends PageQuery<MedicationPlan> {

    @Schema(description = "老人档案 ID（必须有权访问）", example = "301", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "老人档案 ID 不能为空")
    private Long elderId;

    @Schema(description = "计划状态：ACTIVE / DISABLED，不传返回全部", example = "ACTIVE")
    private String status;

    /** MyBatis-Plus 分页对象 */
    public Page<MedicationPlan> toMpPage() {
        return toPage(new Page<>());
    }
}

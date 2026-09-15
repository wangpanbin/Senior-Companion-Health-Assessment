package org.company.nianglin.controller.medication;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.company.nianglin.common.PageResult;
import org.company.nianglin.common.Result;
import org.company.nianglin.dto.MedicationCalendarQuery;
import org.company.nianglin.dto.MedicationPlanCreateDTO;
import org.company.nianglin.dto.MedicationPlanQuery;
import org.company.nianglin.dto.MedicationPlanUpdateDTO;
import org.company.nianglin.dto.MedicationTaskConfirmDTO;
import org.company.nianglin.dto.MedicineQuery;
import org.company.nianglin.service.MedicationService;
import org.company.nianglin.vo.MedicationCalendarVO;
import org.company.nianglin.vo.MedicationConfirmVO;
import org.company.nianglin.vo.MedicationPlanIdVO;
import org.company.nianglin.vo.MedicationPlanVO;
import org.company.nianglin.vo.MedicationTaskVO;
import org.company.nianglin.vo.MedicineVO;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 用药管理与漏服提醒。
 *
 * <p>对应 {@code docs/api/05-medication.md} §1 ~ §9。</p>
 *
 * <h3>本控制器的角色门槛</h3>
 *
 * <table border="1">
 *   <caption>接口与权限</caption>
 *   <tr><th>接口</th><th>注解</th><th>为什么</th></tr>
 *   <tr><td>字典 / 计划查询 / 日历 / 今日待服</td><td>仅「已登录」</td>
 *       <td>数据范围由 Service 的归属校验收窄（家属限绑定、陪诊员限有订单、管理员全量），
 *           注解表达不了「这一份是不是你的」</td></tr>
 *   <tr><td>新增 / 修改 / 停用计划</td><td>{@code hasRole('FAMILY')}</td>
 *       <td>老人的账号是只读的，用药计划必须由家属代录</td></tr>
 *   <tr><td>确认服药</td><td>{@code hasAnyRole('FAMILY','COMPANION')}</td>
 *       <td>家属可确认，陪诊员在现场也能代确认；<b>老人本人不行</b>（只读账号）</td></tr>
 * </table>
 *
 * <p>⚠️ 这里的注解只解决「你是这类角色吗」，真正的安全边界在
 * {@code MedicationServiceImpl#requireElderAccess} —— 少了它，
 * 任何家属都能读到别人家老人的用药计划。</p>
 *
 * @author 银龄伴诊团队
 * @since M6
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/api/medication")
@RequiredArgsConstructor
@Tag(name = "05-用药管理", description = "药品字典、用药计划、服药日历与漏服提醒")
public class MedicationController {

    private final MedicationService medicationService;

    /* ==================== 药品字典 ==================== */

    @Operation(summary = "药品字典分页搜索",
            description = "按通用名 / 商品名模糊搜索。**只返回药品通用资料，不含任何用药建议**。")
    @GetMapping("/dict")
    public Result<PageResult<MedicineVO>> dict(@Valid MedicineQuery query) {
        return Result.success(medicationService.dictPage(query));
    }

    @Operation(summary = "药品详情",
            description = "响应必含 disclaimer（免责声明）。不含建议剂量、适应症判断、替代药等字段。")
    @GetMapping("/dict/{id}")
    public Result<MedicineVO> dictDetail(@Parameter(description = "药品 ID", example = "9001")
                                         @PathVariable("id") Long id) {
        return Result.success(medicationService.dictDetail(id));
    }

    /* ==================== 用药计划 ==================== */

    @Operation(summary = "用药计划列表", description = "须对该老人有访问权限，否则 2006")
    @GetMapping("/plan")
    public Result<PageResult<MedicationPlanVO>> planPage(@Valid MedicationPlanQuery query) {
        return Result.success(medicationService.planPage(query));
    }

    @Operation(summary = "新增用药计划",
            description = "单次用量由家属按医嘱填写，**系统不生成也不校验剂量是否合理**")
    @PreAuthorize("hasRole('FAMILY')")
    @PostMapping("/plan")
    public Result<MedicationPlanIdVO> createPlan(@Valid @RequestBody MedicationPlanCreateDTO dto) {
        Long planId = medicationService.createPlan(dto);
        return Result.success("添加成功", new MedicationPlanIdVO(planId));
    }

    @Operation(summary = "修改用药计划",
            description = "局部更新：不传的字段保持原值，传空串表示清空（如结束日期改为长期）。只影响未来未生成的任务")
    @PreAuthorize("hasRole('FAMILY')")
    @PutMapping("/plan/{id}")
    public Result<Void> updatePlan(@Parameter(description = "用药计划 ID", example = "7001")
                                   @PathVariable("id") Long id,
                                   @Valid @RequestBody MedicationPlanUpdateDTO dto) {
        medicationService.updatePlan(id, dto);
        return Result.success("保存成功", null);
    }

    @Operation(summary = "停用用药计划",
            description = "不物理删除，历史服药记录完整保留；停用后不再生成新任务")
    @PreAuthorize("hasRole('FAMILY')")
    @DeleteMapping("/plan/{id}")
    public Result<Void> disablePlan(@Parameter(description = "用药计划 ID", example = "7001")
                                    @PathVariable("id") Long id) {
        medicationService.disablePlan(id);
        return Result.success("已停用", null);
    }

    /* ==================== 服药任务 ==================== */

    @Operation(summary = "服药日历",
            description = "区间不超过 31 天。汇总中的漏服数按「曾经漏服」统计，含漏服后补记的记录")
    @GetMapping("/task/calendar")
    public Result<MedicationCalendarVO> calendar(@Valid MedicationCalendarQuery query) {
        return Result.success(medicationService.calendar(query));
    }

    @Operation(summary = "今日待服任务",
            description = "老人端大字版首页直接渲染（只读）；家属端在此提供「已服用」按钮")
    @GetMapping("/task/today")
    public Result<List<MedicationTaskVO>> today(
            @Parameter(description = "老人档案 ID", example = "301")
            @RequestParam("elderId") @NotNull(message = "老人档案 ID 不能为空") Long elderId) {
        return Result.success(medicationService.todayTasks(elderId));
    }

    @Operation(summary = "确认服药",
            description = "允许漏服后补记（补记后 wasMissed = true，漏服率统计仍计为漏服）。ELDER 调用返回 403")
    @PreAuthorize("hasAnyRole('FAMILY','COMPANION')")
    @PostMapping("/task/{id}/confirm")
    public Result<MedicationConfirmVO> confirm(@Parameter(description = "服药任务 ID", example = "60003")
                                               @PathVariable("id") Long id,
                                               @Valid @RequestBody(required = false) MedicationTaskConfirmDTO dto) {
        return Result.success("已记录", medicationService.confirm(id, dto == null
                ? new MedicationTaskConfirmDTO() : dto));
    }
}

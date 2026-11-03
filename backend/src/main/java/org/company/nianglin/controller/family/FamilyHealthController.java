package org.company.nianglin.controller.family;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.company.nianglin.common.PageResult;
import org.company.nianglin.common.Result;
import org.company.nianglin.dto.DateRangeQuery;
import org.company.nianglin.dto.HealthTimelineQuery;
import org.company.nianglin.dto.VitalSignCreateDTO;
import org.company.nianglin.dto.VitalSignQuery;
import org.company.nianglin.dto.VitalTrendQuery;
import org.company.nianglin.service.ElderHealthService;
import org.company.nianglin.service.VitalSignService;
import org.company.nianglin.vo.ElderHealthOverviewVO;
import org.company.nianglin.vo.FamilyHealthSummaryVO;
import org.company.nianglin.vo.HealthTimelineVO;
import org.company.nianglin.vo.VitalSignVO;
import org.company.nianglin.vo.VitalTrendVO;
import org.company.nianglin.vo.VitalWithdrawVO;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 家属端的老人健康协同（V3.2 · FR-Overview / FR-Timeline / FR-Summary）。
 *
 * <p>对应 {@code docs/api/12-elder-health.md} §1 ~ §3。</p>
 *
 * <h3>本控制器的角色门槛</h3>
 *
 * <p>三个端点全部 {@code hasRole('FAMILY')}：家属看的是「我绑定的老人」。</p>
 *
 * <p>⚠️ 但注解只解决「你是家属吗」，<b>「这位老人是不是你家的」由
 * {@code ElderService.requireAccessible} 判定</b> ——
 * 少了它，任何家属都能读别人家老人的健康数据。越权时返回 {@code 2006}。</p>
 *
 * @author 银龄伴诊团队
 * @since V3.2
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/api/family")
@RequiredArgsConstructor
@Tag(name = "12-老人健康协同", description = "家庭健康协同首页、老人健康概览、健康时间线与体征记录")
public class FamilyHealthController {

    private final ElderHealthService elderHealthService;
    private final VitalSignService vitalSignService;

    @Operation(summary = "家庭健康协同首页摘要",
            description = "返回当前家属名下每位老人的轻量摘要（今日用药 / 近期就医 / 待处理条数）。"
                    + "没有绑定老人时返回空数组")
    @PreAuthorize("hasRole('FAMILY')")
    @GetMapping("/health/summary")
    public Result<List<FamilyHealthSummaryVO>> summary() {
        return Result.success(elderHealthService.familySummary());
    }

    @Operation(summary = "老人健康概览",
            description = "区间默认最近 30 天、最多 366 天（闭区间）。"
                    + "只返回客观数据（就医次数 / 陪诊次数 / 用药完成率 / 漏服次数 / 待处理事项），"
                    + "不包含任何健康判断；该老人不属于当前家属时返回 2006")
    @PreAuthorize("hasRole('FAMILY')")
    @GetMapping("/elder/{elderId}/health/overview")
    public Result<ElderHealthOverviewVO> overview(
            @Parameter(description = "老人档案 ID", example = "301")
            @PathVariable("elderId") Long elderId,
            @Valid DateRangeQuery query) {
        return Result.success(elderHealthService.overview(elderId, query));
    }

    @Operation(summary = "老人健康时间线",
            description = "把订单状态、陪诊打卡、用药确认、站内信提醒四类事实按时间倒序排成一条线。"
                    + "可按 categories 过滤（ORDER / CHECKIN / MEDICATION / MESSAGE），"
                    + "用 nextCursor 翻页；无数据时返回空数组")
    @PreAuthorize("hasRole('FAMILY')")
    @GetMapping("/elder/{elderId}/health/timeline")
    public Result<HealthTimelineVO> timeline(
            @Parameter(description = "老人档案 ID", example = "301")
            @PathVariable("elderId") Long elderId,
            @Valid HealthTimelineQuery query) {
        return Result.success(elderHealthService.timeline(elderId, query));
    }

    /* ==================== 体征记录（V4 · P2-2） ==================== */

    @Operation(summary = "体征时间线",
            description = "只返回测量数值、时间与录入来源，不含任何解释或区间。"
                    + "按测量时间倒序分页（默认 10 条、上限 100）；区间默认最近 30 天、最多 366 天；"
                    + "该老人不属于当前家属时返回 2006")
    @PreAuthorize("hasRole('FAMILY')")
    @GetMapping("/elder/{elderId}/vitals")
    public Result<PageResult<VitalSignVO>> vitals(
            @Parameter(description = "老人档案 ID", example = "301")
            @PathVariable("elderId") Long elderId,
            @Valid VitalSignQuery query) {
        return Result.success(vitalSignService.listForFamily(elderId, query));
    }

    @Operation(summary = "体征趋势",
            description = "按测量时间升序返回单一体征的数值点；区间内超过 500 条时取最近 500 条，"
                    + "并以 truncated=true 如实告知未全部返回；无数据时 points 为空数组。"
                    + "该老人不属于当前家属时返回 2006")
    @PreAuthorize("hasRole('FAMILY')")
    @GetMapping("/elder/{elderId}/vitals/trend")
    public Result<VitalTrendVO> vitalTrend(
            @Parameter(description = "老人档案 ID", example = "301")
            @PathVariable("elderId") Long elderId,
            @Valid VitalTrendQuery query) {
        return Result.success(vitalSignService.trendForFamily(elderId, query));
    }

    @Operation(summary = "代录体征",
            description = "只保存测量数值；同一老人同一类型同一分钟只允许一条（否则 2008）；"
                    + "数值上下限仅用于拦截误录。该老人不属于当前家属时返回 2006")
    @PreAuthorize("hasRole('FAMILY')")
    @PostMapping("/elder/{elderId}/vitals")
    public Result<VitalSignVO> createVital(
            @Parameter(description = "老人档案 ID", example = "301")
            @PathVariable("elderId") Long elderId,
            @Valid @RequestBody VitalSignCreateDTO dto) {
        return Result.success("记录已保存", vitalSignService.createForFamily(elderId, dto));
    }

    @Operation(summary = "撤回体征记录",
            description = "仅可撤回本人代录的记录（否则 2010）；记录不存在或已撤回返回 2009；"
                    + "所属老人不可访问返回 2006。撤回后同一分钟可重新录入")
    @PreAuthorize("hasRole('FAMILY')")
    @DeleteMapping("/vitals/{recordId}")
    public Result<VitalWithdrawVO> withdrawVital(
            @Parameter(description = "体征记录 ID", example = "123")
            @PathVariable("recordId") Long recordId) {
        return Result.success("记录已撤回", vitalSignService.withdrawByFamily(recordId));
    }
}

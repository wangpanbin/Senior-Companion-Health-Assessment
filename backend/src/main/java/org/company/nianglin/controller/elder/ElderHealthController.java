package org.company.nianglin.controller.elder;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.company.nianglin.common.Result;
import org.company.nianglin.dto.DateRangeQuery;
import org.company.nianglin.dto.HealthTimelineQuery;
import org.company.nianglin.service.ElderHealthService;
import org.company.nianglin.vo.ElderHealthOverviewVO;
import org.company.nianglin.vo.HealthTimelineVO;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 老人端的健康中心（V3.2 · FR-Overview / FR-Timeline）。
 *
 * <p>对应 {@code docs/api/12-elder-health.md} §4 ~ §5。</p>
 *
 * <h3>为什么这两个端点不接受 elderId</h3>
 *
 * <p>老人只能看自己。把 {@code elderId} 放进路径或查询参数，
 * 就等于开了一个「改一下参数就能看别人」的洞 ——
 * 即使服务端会校验，那也是多一次可以出错的地方。
 * 这里<b>一个 elderId 都不收</b>，服务端从登录态直接取自己的档案 ID。</p>
 *
 * <p>老人端拿到的内容是家属端聚合结果的<b>子集</b>：
 * 不含费用、催单、履约风险与家属姓名，只保留与自己相关的事实。</p>
 *
 * @author 银龄伴诊团队
 * @since V3.2
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/api/elder")
@RequiredArgsConstructor
@Tag(name = "12-老人健康协同", description = "老人本人的健康中心")
public class ElderHealthController {

    private final ElderHealthService elderHealthService;

    @Operation(summary = "我的健康概览",
            description = "只返回自己的数据：今日用药完成情况、近期就医次数、待处理事项。"
                    + "不包含费用、催单与履约风险；尚未建立老人档案时返回 2001")
    @PreAuthorize("hasRole('ELDER')")
    @GetMapping("/health/overview")
    public Result<ElderHealthOverviewVO> overview(@Valid DateRangeQuery query) {
        return Result.success(elderHealthService.selfOverview(query));
    }

    @Operation(summary = "我的健康时间线",
            description = "只返回自己的订单、打卡与用药事件。"
                    + "不含发给家属的订单进度提醒；用 nextCursor 翻页；无数据时返回空数组")
    @PreAuthorize("hasRole('ELDER')")
    @GetMapping("/health/timeline")
    public Result<HealthTimelineVO> timeline(@Valid HealthTimelineQuery query) {
        return Result.success(elderHealthService.selfTimeline(query));
    }
}

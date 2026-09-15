package org.company.nianglin.controller.statistics;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.company.nianglin.common.Result;
import org.company.nianglin.constant.RoleConstants;
import org.company.nianglin.dto.AdminOrderQuery;
import org.company.nianglin.dto.AdminUserQuery;
import org.company.nianglin.dto.StatisticsQuery;
import org.company.nianglin.service.StatisticsService;
import org.company.nianglin.vo.ChartDataVO;
import org.company.nianglin.vo.CompanionRankVO;
import org.company.nianglin.vo.MedicationMissedVO;
import org.company.nianglin.vo.OrderStatusStatVO;
import org.company.nianglin.vo.StatisticsOverviewVO;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 数据统计与导出接口。
 *
 * <p>对应 {@code docs/api/09-statistics-export.md} §1 ~ §7。</p>
 *
 * <h3>整类仅 ADMIN</h3>
 *
 * <p>与 M9 同样的理由写在类上：统计接口能一次性看到全平台的订单量、
 * 用户构成与陪诊员绩效，漏加一次注解就是一个数据泄露口。</p>
 *
 * <h3>两个导出接口不返回 {@code Result}</h3>
 *
 * <p>文档 §6 明确要求 Body 为二进制流。因此这两个方法直接写
 * {@code HttpServletResponse}，返回类型是 {@code void} ——
 * 一旦被 {@code Result} 包起来，浏览器下载到的就是一个装着
 * base64 或乱码的 JSON 文件（前端拦截器也不会去解它）。</p>
 *
 * @author 银龄伴诊团队
 * @since M10
 */
@Slf4j
@RestController
@RequestMapping("/api/statistics")
@RequiredArgsConstructor
@PreAuthorize("hasRole('" + RoleConstants.ADMIN + "')")
@Tag(name = "09-数据统计与导出", description = "总览、趋势、分布、排行、漏服率与 Excel 导出")
public class StatisticsController {

    private final StatisticsService statisticsService;

    @Operation(summary = "总览指标",
            description = "订单（总数 / 已完成 / 已取消 / 进行中 / 完成率 / 取消率）、"
                    + "用户（总数 / 新增 / 增长率 / 各角色数）、陪诊员（活跃数 / 平均接单耗时）、"
                    + "用药（任务数 / 已服 / 漏服 / 漏服率）。区间默认最近 30 天，上限 366 天（否则 9002）")
    @GetMapping("/overview")
    public Result<StatisticsOverviewVO> overview(@Valid StatisticsQuery query) {
        return Result.success(statisticsService.overview(query));
    }

    @Operation(summary = "订单趋势",
            description = "三条序列：新增 / 完成 / 取消。无数据的日期<b>补 0</b>，"
                    + "categories 与每个 series.data 长度严格一致")
    @GetMapping("/order-trend")
    public Result<ChartDataVO> orderTrend(@Valid StatisticsQuery query) {
        return Result.success(statisticsService.orderTrend(query));
    }

    @Operation(summary = "订单状态分布",
            description = "六种状态全部返回（数量为 0 的也返回），保证饼图图例完整")
    @GetMapping("/order-status")
    public Result<List<OrderStatusStatVO>> orderStatus(@Valid StatisticsQuery query) {
        return Result.success(statisticsService.orderStatus(query));
    }

    @Operation(summary = "陪诊员接单排行",
            description = "只统计资质已通过审核的陪诊员，接单数为 0 的不进榜；"
                    + "metric 支持 ORDER_COUNT（默认）/ SCORE，limit 默认 10、最大 50")
    @GetMapping("/companion-rank")
    public Result<List<CompanionRankVO>> companionRank(@Valid StatisticsQuery query) {
        return Result.success(statisticsService.companionRank(query));
    }

    @Operation(summary = "漏服率统计",
            description = "三条序列：任务总数 / 已服用 / 漏服，并附区间汇总。"
                    + "漏服口径为 was_missed = 1（含漏服后补记），与服药日历一致")
    @GetMapping("/medication-missed")
    public Result<MedicationMissedVO> medicationMissed(@Valid StatisticsQuery query) {
        return Result.success(statisticsService.medicationMissed(query));
    }

    @Operation(summary = "导出订单 Excel",
            description = "筛选条件与 /api/admin/order 完全一致（导出结果 = 页面当前筛选结果）。"
                    + "返回 xlsx 二进制流，不走统一响应结构。超过行数上限返回 9001")
    @GetMapping("/export/order")
    public void exportOrder(@Valid AdminOrderQuery query, HttpServletResponse response) {
        statisticsService.exportOrders(query, response);
    }

    @Operation(summary = "导出用户 Excel",
            description = "筛选条件与 /api/admin/user 完全一致。导出的表格<b>不含</b>密码、"
                    + "身份证号与完整手机号。超过行数上限返回 9001")
    @GetMapping("/export/user")
    public void exportUser(@Valid AdminUserQuery query, HttpServletResponse response) {
        statisticsService.exportUsers(query, response);
    }
}

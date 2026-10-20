package org.company.nianglin.controller.statistics;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.company.nianglin.common.Result;
import org.company.nianglin.constant.RoleConstants;
import org.company.nianglin.dto.CompanionTrendQuery;
import org.company.nianglin.dto.DateRangeQuery;
import org.company.nianglin.service.CompanionStatisticsService;
import org.company.nianglin.vo.CompanionIncomeVO;
import org.company.nianglin.vo.CompanionServiceProfileVO;
import org.company.nianglin.vo.CompanionTrendVO;
import org.company.nianglin.vo.CompanionWorkbenchOverviewVO;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 陪诊员工作台统计接口（V3.3）。
 *
 * <h3>为什么单独一个 Controller 类</h3>
 *
 * <p>同包的 {@code StatisticsController} 是<b>类级</b>
 * {@code @PreAuthorize("hasRole('ADMIN')")}。如果把陪诊员端点挂在它下面，
 * 就得靠方法级注解去覆盖类级注解 —— 那是「靠例外活着的规则」，
 * 漏一个方法就是一次数据泄露。所以另起一个类，
 * 类级注解与端点集合一一对应，越权矩阵也才写得出来。</p>
 *
 * <h3>陪诊员身份从登录态取</h3>
 *
 * <p>四个端点都<b>不接收 {@code companionId} 入参</b>：
 * 请求里即使带了 {@code ?companionId=999}，返回的也只会是当前登录者自己的数据
 * —— 因为压根没有读它的地方。</p>
 *
 * @author 银龄伴诊团队
 * @since V3.3
 */
@Slf4j
@RestController
@RequestMapping("/api/statistics/companion")
@RequiredArgsConstructor
@PreAuthorize("hasRole('" + RoleConstants.COMPANION + "')")
@Tag(name = "09-数据统计与导出", description = "陪诊员工作台：概览 / 趋势 / 服务画像 / 收入中心")
public class CompanionStatisticsController {

    private final CompanionStatisticsService companionStatisticsService;

    @Operation(summary = "工作台概览",
            description = "统计区间内的完成单数 / 服务时长（分钟）/ 完成率 / 平均评分 / 区间收入。"
                    + "区间闭区间、默认最近 30 天、上限 366 天（否则 9002）。"
                    + "<b>完成率分母只算「已完成 + 已取消」</b>，被拒的单不计入分母")
    @GetMapping("/me")
    public Result<CompanionWorkbenchOverviewVO> overview(@Valid DateRangeQuery query) {
        return Result.success(companionStatisticsService.overview(query));
    }

    @Operation(summary = "工作台趋势",
            description = "维度 ORDER（完成订单）/ INCOME（收入）/ RATING（评分），"
                    + "粒度 DAY / WEEK / MONTH。无数据的桶<b>补 0</b>，"
                    + "categories 与每条 series.data 长度严格一致")
    @GetMapping("/me/trend")
    public Result<CompanionTrendVO> trend(@Valid CompanionTrendQuery query) {
        return Result.success(companionStatisticsService.trend(query));
    }

    @Operation(summary = "服务画像",
            description = "<b>历史累计口径</b>，不随统计区间浮动：平均评分 / 累计完成单数 / "
                    + "累计服务老人数 / 累计准时率 / 累计完成率 / 累计服务时长 / 擅长服务标签。"
                    + "擅长服务取科室维度 TOP 5，<b>样本不足 5 单的科室不展示</b>")
    @GetMapping("/me/profile")
    public Result<CompanionServiceProfileVO> profile() {
        return Result.success(companionStatisticsService.profile());
    }

    @Operation(summary = "收入中心",
            description = "统计区间内的订单金额 / 实际收入 / 待结算 / 已结算 / 代垫合计 / 服务费合计。"
                    + "实际收入 = SUM(COALESCE(actual_fee, fee))，未结算时回退合同服务费；"
                    + "<b>待结算 + 已结算 = 实际收入</b>。一期只有线上记账 + 线下结算，无在线支付")
    @GetMapping("/me/income")
    public Result<CompanionIncomeVO> income(@Valid DateRangeQuery query) {
        return Result.success(companionStatisticsService.income(query));
    }
}

package org.company.nianglin.controller.admin;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.company.nianglin.common.Result;
import org.company.nianglin.constant.RoleConstants;
import org.company.nianglin.dto.DateRangeQuery;
import org.company.nianglin.dto.OperationsEffectQuery;
import org.company.nianglin.dto.OperationsGapQuery;
import org.company.nianglin.dto.OperationsTrendQuery;
import org.company.nianglin.service.OperationsService;
import org.company.nianglin.vo.OperationsEffectVO;
import org.company.nianglin.vo.OperationsExceptionVO;
import org.company.nianglin.vo.OperationsGapVO;
import org.company.nianglin.vo.OperationsHealthVO;
import org.company.nianglin.vo.OperationsOverviewVO;
import org.company.nianglin.vo.OperationsTrendVO;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台运营驾驶舱接口（V3.5 · T4）。
 *
 * <h3>整类的 {@code @PreAuthorize} 写在类上</h3>
 *
 * <p>与 {@code AdminController} 同一条理由：驾驶舱能一次性看到全平台的订单量、
 * 投诉率、评分与陪诊员表现，漏加一次注解就是一个数据泄露口。
 * 写在类上，新增第 5 个端点时默认继承这条规则。</p>
 *
 * <h3>Controller 不承载业务规则</h3>
 *
 * <p>区间解析、维度校验、口径计算全在 {@code OperationsServiceImpl} 里，
 * 这里只做「取参 → 调 Service → 包 Result」。</p>
 *
 * @author 银龄伴诊团队
 * @since V3.5
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/operations")
@RequiredArgsConstructor
@PreAuthorize("hasRole('" + RoleConstants.ADMIN + "')")
@Tag(name = "08-管理后台", description = "平台运营驾驶舱：概览 / 异常中心 / 趋势 / 健康度")
public class OperationsController {

    private final OperationsService operationsService;

    @Operation(summary = "驾驶舱概览",
            description = "今日新增 / 区间完成 / 服务中 / 待接单四项计数，"
                    + "履约率（完成 ÷（完成 + 取消））、平均接单时间、投诉率、平均评分。"
                    + "区间闭区间、默认最近 30 天、上限 366 天（否则 9002）。"
                    + "<b>履约率与健康度里的「完成率（按时）」是两个指标</b>，不是一个数")
    @GetMapping("/overview")
    public Result<OperationsOverviewVO> overview(@Valid DateRangeQuery query) {
        return Result.success(operationsService.overview(query));
    }

    @Operation(summary = "异常中心",
            description = "四类异常恒定全部返回（数量为 0 也返回，保证卡片不消失）："
                    + "需人工介入订单 / 超匹配预警时间订单 / 投诉率异常陪诊员 / 用药漏服异常。"
                    + "<b>前两项与履约风险中心同源</b>（复用 V3.1 的判定结果，不另算一套）；"
                    + "每项的 linkUrl 指向前端真实存在的处理页")
    @GetMapping("/exceptions")
    public Result<OperationsExceptionVO> exceptions(@Valid DateRangeQuery query) {
        return Result.success(operationsService.exceptions(query));
    }

    @Operation(summary = "驾驶舱趋势",
            description = "维度 ORDER（订单量）/ COMPANION_ACTIVE（活跃陪诊员数）/ "
                    + "FULFILLMENT_RATE（履约率）/ COMPLAINT（投诉订单数）/ REVIEW（评价条数）/ "
                    + "MEDICATION（用药任务数）；"
                    + "区间 TODAY / LAST_7D / LAST_30D / CUSTOM（此时读 startDate / endDate）。"
                    + "无数据的桶<b>补 0</b>，categories 与每条 series.data 长度严格一致")
    @GetMapping("/trend")
    public Result<OperationsTrendVO> trend(@Valid OperationsTrendQuery query) {
        return Result.success(operationsService.trend(query));
    }

    @Operation(summary = "平台健康度",
            description = "六个并列指标：履约率 / 接单速度（分钟）/ 完成率（按时）/ 投诉率 / "
                    + "平均评分 / 活跃陪诊员数。<b>不给合成分</b>，也不出现「健康指数」这类无法解释的数")
    @GetMapping("/health")
    public Result<OperationsHealthVO> health(@Valid DateRangeQuery query) {
        return Result.success(operationsService.health(query));
    }

    @Operation(summary = "供给时段缺口分析",
            description = "按「周内第几天 × 小时」分桶（固定 7×24 = 168 个桶，<b>空桶也返回</b>）："
                    + "待接未接单数（按 visit_time 落桶）/ 该时段可服务的陪诊员数 / 缺口（需求 − 供给，> 0 即为缺口）。"
                    + "供给数按陪诊员自己的<b>可服务时段</b>分桶（结构化表优先，存量自由文本回退解析），"
                    + "同一陪诊员在同一桶里只计 1 次；"
                    + "解析不出时段的陪诊员由 companionsWithoutSlots 如实披露，不混进分母。"
                    + "区间 LAST_7D / LAST_30D / CUSTOM（此时读 startDate / endDate）")
    @GetMapping("/gap")
    public Result<OperationsGapVO> gap(@Valid OperationsGapQuery query) {
        return Result.success(operationsService.gap(query));
    }

    @Operation(summary = "干预效果对比",
            description = "同一指标在两期区间上的对比。<b>两期由同一个计算器算两遍</b>，"
                    + "不会对比出「两个公式的差异」。"
                    + "metric 必须是已登记且可计算的指标 key，否则 400（不会猜一个算法算出来）；"
                    + "from / to 是后一期（默认最近 30 天、上限 366 天，否则 9002）；"
                    + "compareFrom / compareTo 是前一期，不传时自动取「与后一期等长、紧邻其前」的区间；"
                    + "delta 是差值（百分比指标为百分点），两侧格式不同时返回「—」")
    @GetMapping("/effect")
    public Result<OperationsEffectVO> effect(@Valid OperationsEffectQuery query) {
        return Result.success(operationsService.effect(query));
    }
}

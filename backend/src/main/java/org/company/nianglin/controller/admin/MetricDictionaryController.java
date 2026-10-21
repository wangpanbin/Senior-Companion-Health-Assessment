package org.company.nianglin.controller.admin;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.company.nianglin.common.Result;
import org.company.nianglin.constant.RoleConstants;
import org.company.nianglin.service.support.metrics.MetricDictionaryExporter;
import org.company.nianglin.vo.MetricDictionaryVO;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 指标字典只读导出接口（V3.6 · T7 · FR-Trace）。
 *
 * <h3>整类的 {@code @PreAuthorize} 写在类上</h3>
 *
 * <p>与 {@code AdminController} / {@code OperationsController} 同一条理由：
 * 字典里写着全平台的统计口径（订单量、履约率、投诉率、收入），
 * 漏加一次注解就是一个数据泄露口。写在类上，
 * 将来新增第 2 个端点时默认继承这条规则。</p>
 *
 * <h3>只读，不改任何东西</h3>
 *
 * <p>本端点只做「把机器面投影成 JSON」，不接收任何业务参数、
 * 不写库、不触发统计计算。口径的修改只能通过
 * {@code metrics/metric-dictionary.yml} 的变更 + 字典的版本升级，
 * <b>不提供运行时改口径的入口</b> —— 否则同一时刻两个看板可能读到两份口径。</p>
 *
 * @author 银龄伴诊团队
 * @since V3.6
 */
@Slf4j
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
@PreAuthorize("hasRole('" + RoleConstants.ADMIN + "')")
@Tag(name = "08-管理后台", description = "指标字典只读导出")
public class MetricDictionaryController {

    private final MetricDictionaryExporter exporter;

    @Operation(summary = "指标字典只读导出",
            description = "导出指标字典的<b>口径定义</b>（不含统计值）："
                    + "指标 key / 名称 / 统计范围 / 统计时间 / 数据来源 / 计算公式 / 口径版本 / 出口端点。"
                    + "供运营对账与开发排查使用。"
                    + "本端点<b>只读</b>，不提供修改口径的入口")
    @GetMapping("/metrics")
    public Result<MetricDictionaryVO> metrics() {
        return Result.success(exporter.export());
    }
}

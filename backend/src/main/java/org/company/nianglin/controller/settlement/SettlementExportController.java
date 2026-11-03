package org.company.nianglin.controller.settlement;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.company.nianglin.constant.RoleConstants;
import org.company.nianglin.dto.AdminSettlementQuery;
import org.company.nianglin.service.SettlementService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 结算单导出接口（V4 · P2-1）。
 *
 * <p>对应 {@code docs/api/09-statistics-export.md} §9。</p>
 *
 * <h3>为什么单独一个控制器</h3>
 *
 * <p>导出路径按契约是 {@code /api/settlement/export}，既不在
 * {@code /api/admin} 也不在 {@code /api/statistics} 前缀下。
 * Spring 的类级 {@code @RequestMapping} 只能拼前缀，
 * 因此挂在既有控制器上会得到
 * {@code /api/statistics/api/settlement/export} 这种错误路径。
 * 拆成独立控制器后，类级 ADMIN 注解依旧是一次性收口，
 * 与 {@code SecurityConfig} 里新增的 {@code /api/settlement/**} 规则双保险。</p>
 *
 * <h3>为什么返回 void 而不是 {@code Result}</h3>
 *
 * <p>Body 必须是 xlsx 二进制流。一旦包成 {@code Result}，
 * 浏览器下载到的就是一个装着乱码的 JSON 文件，前端拦截器也不会去解它。</p>
 *
 * @author 银龄伴诊团队
 * @since V4 · P2-1
 */
@Slf4j
@RestController
@RequestMapping("/api/settlement")
@RequiredArgsConstructor
@PreAuthorize("hasRole('" + RoleConstants.ADMIN + "')")
@Tag(name = "09-数据统计与导出", description = "结算单 Excel 导出")
public class SettlementExportController {

    private final SettlementService settlementService;

    @Operation(summary = "导出结算单 Excel",
            description = "筛选条件与 /api/admin/settlement 完全一致（paymentStatus / keyword / "
                    + "startDate / endDate），周期按服务完成时间。"
                    + "返回 xlsx 二进制流，不走统一响应结构；超过行数上限返回 9001。"
                    + "金额全部由服务端计算，接口不接收任何金额入参；姓名列脱敏")
    @GetMapping("/export")
    public void export(@Valid AdminSettlementQuery query, HttpServletResponse response) {
        settlementService.export(query, response);
    }
}

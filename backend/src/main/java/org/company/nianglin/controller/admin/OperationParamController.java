package org.company.nianglin.controller.admin;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.company.nianglin.common.PageResult;
import org.company.nianglin.common.Result;
import org.company.nianglin.dto.OperationParamUpdateDTO;
import org.company.nianglin.service.OperationParamService;
import org.company.nianglin.vo.OperLogVO;
import org.company.nianglin.vo.OperationParamVO;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 管理端运营参数配置（仅 ADMIN）。
 *
 * <p>参数值改完由 {@code OperationParamService} 在事务提交后刷新本实例缓存，
 * 业务读取点下一次调用即生效，无需重启或发版。</p>
 *
 * @author 银龄伴诊团队
 * @since V4 · P2-3
 */
@Tag(name = "管理端-运营参数", description = "运营阈值在线调整（sys_dict.OPS_PARAM）")
@RestController
@RequestMapping("/api/admin/operation-param")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class OperationParamController {

    private final OperationParamService operationParamService;

    @Operation(summary = "运营参数列表", description = "返回当前生效值、application.yml 默认值、可调范围与最近更新时间")
    @GetMapping
    public Result<List<OperationParamVO>> list() {
        return Result.success(operationParamService.list());
    }

    @Operation(summary = "修改运营参数", description = "写 sys_dict 并落 admin_oper_log，事务提交后热生效")
    @PutMapping("/{code}")
    public Result<Void> update(@PathVariable String code,
                               @Valid @RequestBody OperationParamUpdateDTO dto) {
        operationParamService.update(code, dto);
        return Result.success();
    }

    @Operation(summary = "参数变更留痕", description = "按操作时间倒序返回 UPDATE_OPERATION_PARAM 类型的操作日志")
    @GetMapping("/logs")
    public Result<PageResult<OperLogVO>> logs(@RequestParam(defaultValue = "1") long page,
                                              @RequestParam(defaultValue = "10") long size) {
        return Result.success(operationParamService.logs(page, size));
    }
}

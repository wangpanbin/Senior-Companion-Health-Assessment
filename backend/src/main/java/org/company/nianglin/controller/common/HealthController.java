package org.company.nianglin.controller.common;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.company.nianglin.common.Result;
import org.company.nianglin.common.ResultCode;
import org.company.nianglin.exception.BusinessException;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 健康检查与骨架自检接口。
 *
 * <p>用途：</p>
 * <ol>
 *   <li>验证后端已启动、统一响应结构生效</li>
 *   <li>验证全局异常处理器生效（{@code /api/health/demo-error}）</li>
 *   <li>给前端与部署脚本提供一个探活地址</li>
 * </ol>
 *
 * @author 银龄伴诊团队
 */
@Slf4j
@RestController
@RequestMapping("/api/health")
@RequiredArgsConstructor
@Tag(name = "00-通用", description = "健康检查与服务自检")
public class HealthController {

    private final Environment env;

    @Operation(summary = "服务健康检查", description = "无需登录。返回服务名、运行环境与服务器当前时间，可用于前端探活与部署脚本健康检查。")
    @GetMapping
    public Result<Map<String, Object>> health() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("application", env.getProperty("spring.application.name", "nianglin"));
        data.put("profiles", String.join(",", env.getActiveProfiles()));
        data.put("javaVersion", System.getProperty("java.version"));
        data.put("serverTime", LocalDateTime.now());
        data.put("status", "UP");
        return Result.success(data);
    }

    @Operation(summary = "统一响应结构自检", description = "返回一个标准 Result 结构，用于验证统一响应封装是否生效。")
    @GetMapping("/demo-success")
    public Result<Map<String, Object>> demoSuccess() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("message", "统一响应结构工作正常");
        data.put("hint", "前端拿到的结构应为 { code, message, data }");
        return Result.success(data);
    }

    @Operation(summary = "全局异常处理自检", description = "故意抛出业务异常，用于验证 GlobalExceptionHandler 是否把异常转成了标准 Result 结构。")
    @GetMapping("/demo-error")
    public Result<Void> demoError() {
        // TODO(W16)：交付前删除该自检接口
        throw new BusinessException(ResultCode.NOT_IMPLEMENTED, "这是一个演示异常，用于验证全局异常处理器");
    }
}

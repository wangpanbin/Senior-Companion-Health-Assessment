package org.company.nianglin.controller.common;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.company.nianglin.common.Result;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
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

    /** 生产环境标识 —— 只有非 prod 才返回运行环境细节 */
    private static final String PROD_PROFILE = "prod";

    @Operation(summary = "服务健康检查",
            description = "无需登录，可用于前端探活与部署脚本。"
                    + "任何 profile 都只保证返回 <b>status</b> 与 <b>serverTime</b>；"
                    + "<b>prod 环境不返回</b> application / profiles / javaVersion —— "
                    + "匿名端点暴露「是不是 dev、JDK 大版本」等于给指纹识别送现成答案，"
                    + "环境细节请走需要登录的运维端点。")
    @GetMapping
    public Result<Map<String, Object>> health() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", "UP");
        data.put("serverTime", LocalDateTime.now());
        if (!env.acceptsProfiles(Profiles.of(PROD_PROFILE))) {
            data.put("application", env.getProperty("spring.application.name", "nianglin"));
            data.put("profiles", String.join(",", env.getActiveProfiles()));
            data.put("javaVersion", System.getProperty("java.version"));
        }
        return Result.success(data);
    }

    @Operation(summary = "统一响应结构自检",
            description = "返回一个标准 Result 结构，用于验证统一响应封装是否生效。"
                    + "<b>⚠️ 该端点需要登录</b> —— 匿名白名单里只有精确的 <code>/api/health</code>，"
                    + "本路径不在其中，未带令牌访问会拿到 401。"
                    + "当探活地址用请用 <code>/api/health</code>。")
    @GetMapping("/demo-success")
    public Result<Map<String, Object>> demoSuccess() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("message", "统一响应结构工作正常");
        data.put("hint", "前端拿到的结构应为 { code, message, data }");
        return Result.success(data);
    }
}

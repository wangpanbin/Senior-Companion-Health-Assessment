package org.company.nianglin.controller.common;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.company.nianglin.common.Result;
import org.company.nianglin.constant.RoleConstants;
import org.company.nianglin.security.SecurityUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 权限探针。
 *
 * <p><b>为什么需要它</b>：M2 的验收标准是「4 角色 × 3 类接口 = 12 条越权用例全部通过」，
 * 但此时除认证模块外的业务接口（订单、用药、管理后台）都还没落地，没有对象可测。
 * 与其把越权用例推迟到 M4/M9 再补，不如提供一组<b>只有角色门槛、没有业务逻辑</b>的探针接口，
 * 让权限矩阵现在就能被真实 HTTP 请求验证一遍。</p>
 *
 * <p>各业务模块落地后，本控制器可以整体删除 —— 它不承载任何业务语义，
 * 删除不会影响功能。真正长期有效的回归测试是 {@code PermissionMatrixTest}。</p>
 *
 * <p>⚠️ 返回体只回显「你是谁、你通过了哪道门」，不含任何业务数据或敏感字段。</p>
 *
 * @author 银龄伴诊团队
 */
@Slf4j
@RestController
@RequestMapping("/api/common/perm-probe")
@RequiredArgsConstructor
@Tag(name = "99-权限探针", description = "M2 权限矩阵验收用，各业务模块落地后可整体删除")
public class PermissionProbeController {

    @Operation(summary = "登录即可访问", description = "任意已登录角色都应返回成功；用于验证 authenticated 兜底规则")
    @GetMapping("/authenticated")
    public Result<Map<String, Object>> authenticated() {
        return Result.success(identity("AUTHENTICATED"));
    }

    @Operation(summary = "仅老年患者可访问", description = "验证 hasRole(ELDER)")
    @PreAuthorize("hasRole('" + RoleConstants.ELDER + "')")
    @GetMapping("/elder")
    public Result<Map<String, Object>> elderOnly() {
        return Result.success(identity(RoleConstants.ELDER));
    }

    @Operation(summary = "仅家属可访问", description = "验证 hasRole(FAMILY)")
    @PreAuthorize("hasRole('" + RoleConstants.FAMILY + "')")
    @GetMapping("/family")
    public Result<Map<String, Object>> familyOnly() {
        return Result.success(identity(RoleConstants.FAMILY));
    }

    @Operation(summary = "仅陪诊员可访问", description = "验证 hasRole(COMPANION)")
    @PreAuthorize("hasRole('" + RoleConstants.COMPANION + "')")
    @GetMapping("/companion")
    public Result<Map<String, Object>> companionOnly() {
        return Result.success(identity(RoleConstants.COMPANION));
    }

    @Operation(summary = "仅管理员可访问", description = "验证 hasRole(ADMIN)")
    @PreAuthorize("hasRole('" + RoleConstants.ADMIN + "')")
    @GetMapping("/admin")
    public Result<Map<String, Object>> adminOnly() {
        return Result.success(identity(RoleConstants.ADMIN));
    }

    @Operation(summary = "老人写操作探针",
            description = "角色门槛只放行 ELDER。老人账号调用应被 ElderReadOnlyInterceptor 拦截并返回 403（只读模式），"
                    + "其他任意角色调用应被 hasRole 拦截并返回 403（无操作权限）")
    @PreAuthorize("hasRole('" + RoleConstants.ELDER + "')")
    @PostMapping("/elder-write")
    public Result<Map<String, Object>> elderWriteProbe() {
        // 正常情况下永远走不到这里：老人账号在拦截器就被拦下，非老人账号被 @PreAuthorize 拦下
        log.warn("老人写操作探针被放行 —— 说明只读拦截或角色校验失效 | userId={}", SecurityUtils.currentUserId());
        return Result.success(identity("ELDER_WRITE_UNEXPECTED"));
    }

    /** 身份回显：让验收时一眼看出「这条请求是以谁的身份通过的」 */
    private Map<String, Object> identity(String gate) {
        var loginUser = SecurityUtils.currentUser();
        Map<String, Object> body = new LinkedHashMap<>(4);
        body.put("gate", gate);
        body.put("userId", loginUser.userId());
        body.put("username", loginUser.username());
        body.put("role", loginUser.role());
        return body;
    }
}

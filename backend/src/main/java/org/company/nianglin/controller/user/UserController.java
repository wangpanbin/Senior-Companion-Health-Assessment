package org.company.nianglin.controller.user;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.company.nianglin.common.Result;
import org.company.nianglin.constant.RoleConstants;
import org.company.nianglin.dto.CompanionApplyDTO;
import org.company.nianglin.dto.CompanionWorkStatusUpdateDTO;
import org.company.nianglin.dto.DeactivateAccountDTO;
import org.company.nianglin.dto.ProfileUpdateDTO;
import org.company.nianglin.service.CompanionService;
import org.company.nianglin.service.UserService;
import org.company.nianglin.vo.CompanionApplicationVO;
import org.company.nianglin.vo.CompanionApplyResultVO;
import org.company.nianglin.vo.CompanionProfileVO;
import org.company.nianglin.vo.UserInfoVO;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户资料与陪诊员资质接口。
 *
 * <p>对应 {@code docs/api/02-elder-family.md} §1 ~ §5。</p>
 *
 * <h3>为什么分成两个 Controller 都挂在 {@code /api/user}</h3>
 *
 * <p>老人档案有 7 个接口、用户资料与资质有 5 个，全部塞进一个类会得到
 * 一个 300 行的 Controller。按「操作对象」拆成
 * {@code UserController}（账号与资质）和 {@code ElderController}（老人档案），
 * 两者的鉴权规则也不同：前者「已登录即可」，后者「必须是家属且是绑定人」。
 * 拆开后看类名就知道这条接口的门槛在哪。</p>
 *
 * <h3>关于「权限：已登录」与老人只读的叠加</h3>
 *
 * <p>本类的 {@code PUT /profile}、{@code POST /companion/apply} 标注的是「已登录」，
 * 但老人账号（ELDER）仍会被 {@code ElderReadOnlyInterceptor} 拦成 403 ——
 * 两条规则是叠加生效的，不是二选一。这是刻意的：只读规则只有一条，
 * 没有例外清单，评审时不用去翻「哪些写接口对老人开放」。</p>
 *
 * @author 银龄伴诊团队
 * @since M3
 */
@Slf4j
@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
@Tag(name = "02-用户与档案", description = "当前用户资料、陪诊员资质申请与公开资料")
public class UserController {

    private final UserService userService;
    private final CompanionService companionService;

    /* ==================== 当前用户资料 ==================== */

    @Operation(summary = "获取当前用户资料",
            description = "返回登录用户的昵称、角色、脱敏手机号等；手机号与密码不在返回范围内")
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/profile")
    public Result<UserInfoVO> getProfile() {
        return Result.success(userService.getProfile());
    }

    @Operation(summary = "更新当前用户资料",
            description = "只支持昵称与头像。手机号走绑定流程、密码走 /api/auth/password，均不在此接口。"
                    + "老人账号（ELDER）调用会被只读规则拦截（403）")
    @PreAuthorize("isAuthenticated()")
    @PutMapping("/profile")
    public Result<Void> updateProfile(@Valid @RequestBody ProfileUpdateDTO dto) {
        userService.updateProfile(dto);
        return Result.success("保存成功", null);
    }

    /* ==================== 陪诊员资质 ==================== */

    @Operation(summary = "提交陪诊员资质申请",
            description = "任何已登录账号都可申请（老人账号除外）。身份证号服务端 AES 加密存储。"
                    + "提交后角色不变，只有管理员审核通过才升级为 COMPANION。"
                    + "已有待审核申请或已通过审核时会拒绝")
    @PreAuthorize("isAuthenticated()")
    @PostMapping("/companion/apply")
    public Result<CompanionApplyResultVO> applyCompanion(
            @Valid @RequestBody CompanionApplyDTO dto) {
        return Result.success("提交成功，请等待管理员审核", companionService.apply(dto));
    }

    @Operation(summary = "查询自己的资质申请状态",
            description = "返回最近一次申请的状态与驳回原因；从未申请过时 data 为 null")
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/companion/application")
    public Result<CompanionApplicationVO> myCompanionApplication() {
        return Result.success(companionService.myApplication());
    }

    @Operation(summary = "切换陪诊员接单状态",
            description = "陪诊员自助控制是否接受新订单：AVAILABLE-可接单 / REST-休息中 / OFFLINE-离线。"
                    + "**切换只影响新订单的匹配，不影响已接订单** —— "
                    + "已接单或服务中的订单继续按订单状态机推进，不会被中断或取消。"
                    + "资质未通过时返回 2003；状态值非法时返回 400 并在消息中列出合法取值。")
    @PreAuthorize("hasRole('" + RoleConstants.COMPANION + "')")
    @PutMapping("/companion/work-status")
    public Result<CompanionProfileVO> updateCompanionWorkStatus(
            @Valid @RequestBody CompanionWorkStatusUpdateDTO dto) {
        return Result.success("接单状态已更新", companionService.updateWorkStatus(dto));
    }

    @Operation(summary = "查询陪诊员公开资料",
            description = "仅返回已通过审核的陪诊员。不返回身份证号、联系电话、证件图片与驳回原因")
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/companion/{id}")
    public Result<CompanionProfileVO> companionProfile(
            @Parameter(description = "陪诊员**用户 ID**（不是 companion_profile 主键）", example = "301")
            @PathVariable("id") Long id) {
        return Result.success(companionService.publicProfile(id));
    }

    /* ==================== 注销与数据导出（V4 · P0-1） ==================== */

    @Operation(summary = "注销当前账号",
            description = "仅家属（FAMILY）与陪诊员（COMPANION）可自助注销。"
                    + "老人账号（ELDER）被只读规则拦截（403）；管理员（ADMIN）无注销入口（403），走后台封禁。"
                    + "需复验当前密码并原样输入确认文本「我已知晓注销后果」。"
                    + "存在进行中订单（待接单 / 已接单 / 服务中）时返回 11001，data 携带 pendingOrderCount。"
                    + "成功后账号逻辑删除并禁用，姓名 / 手机号 / 身份证去标识化，全部已签发令牌立即失效（401）")
    @PreAuthorize("hasAnyRole('" + RoleConstants.FAMILY + "','" + RoleConstants.COMPANION + "')")
    @PostMapping("/deactivate")
    public Result<Void> deactivate(@Valid @RequestBody DeactivateAccountDTO dto) {
        userService.deactivate(dto);
        return Result.success("账号已注销", null);
    }

    @Operation(summary = "导出我的个人数据",
            description = "仅家属（FAMILY）与陪诊员（COMPANION）可导出本人数据；"
                    + "老人账号（ELDER）与管理员（ADMIN）均返回 403。"
                    + "直写 application/octet-stream（xlsx，5 个 sheet：个人资料 / 绑定老人 / 订单 / 评价 / 消息），"
                    + "姓名与手机号全部脱敏，不含身份证号与密码哈希")
    @PreAuthorize("hasAnyRole('" + RoleConstants.FAMILY + "','" + RoleConstants.COMPANION + "')")
    @GetMapping("/data-export")
    public void dataExport(HttpServletResponse response) {
        userService.dataExport(response);
    }
}

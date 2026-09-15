package org.company.nianglin.controller.admin;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.company.nianglin.common.PageResult;
import org.company.nianglin.common.Result;
import org.company.nianglin.constant.RoleConstants;
import org.company.nianglin.dto.AdminAuditQuery;
import org.company.nianglin.dto.AdminComplaintQuery;
import org.company.nianglin.dto.AdminOrderQuery;
import org.company.nianglin.dto.AdminUserQuery;
import org.company.nianglin.dto.ArbitrateDTO;
import org.company.nianglin.dto.AuditDecisionDTO;
import org.company.nianglin.dto.ComplaintHandleDTO;
import org.company.nianglin.dto.OperLogQuery;
import org.company.nianglin.dto.ResetPasswordDTO;
import org.company.nianglin.dto.UserDisableDTO;
import org.company.nianglin.dto.UserEnableDTO;
import org.company.nianglin.service.AdminService;
import org.company.nianglin.vo.AdminOrderVO;
import org.company.nianglin.vo.AdminUserVO;
import org.company.nianglin.vo.ArbitrateResultVO;
import org.company.nianglin.vo.AuditApplicationVO;
import org.company.nianglin.vo.AuditDecisionResultVO;
import org.company.nianglin.vo.AuditPageVO;
import org.company.nianglin.vo.ComplaintHandleResultVO;
import org.company.nianglin.vo.ComplaintVO;
import org.company.nianglin.vo.OperLogVO;
import org.company.nianglin.vo.ResetPasswordResultVO;
import org.company.nianglin.vo.UserStatusResultVO;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理后台接口。
 *
 * <p>对应 {@code docs/api/08-admin.md} §1 ~ §12。</p>
 *
 * <h3>整类的 {@code @PreAuthorize} 写在类上</h3>
 *
 * <p>文档顶部第一条硬性约束是「本组接口<b>仅 ADMIN 可访问</b>，其他角色一律 403」。
 * 把注解写在类上是刻意的：12 个接口各写一遍，将来新增第 13 个时忘写一次，
 * 就是一个能看全平台数据的洞 —— 而漏写不会报错、不会被编译拦下，只会在某天被翻出来。
 * 写在类上，新方法默认继承这条规则，漏写是「多一次注解」而不是「少一次保护」。</p>
 *
 * <p>类级注解与 {@code SecurityConfig} 的路径规则互不冲突：后者只管
 * 「要不要登录」，前者管「是不是管理员」。</p>
 *
 * @author 银龄伴诊团队
 * @since M9
 */
@Slf4j
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
@PreAuthorize("hasRole('" + RoleConstants.ADMIN + "')")
@Tag(name = "08-管理后台", description = "资质审核、用户管理、订单纠纷、投诉处理与操作日志")
public class AdminController {

    private final AdminService adminService;

    /* ==================== 资质审核 ==================== */

    @Operation(summary = "资质申请列表",
            description = "支持按状态 / 姓名 / 手机号 / 提交时间区间筛选。"
                    + "响应额外返回 counts（三种状态的整表数量），让管理端标签栏一次拿全")
    @GetMapping("/companion/audit")
    public Result<AuditPageVO> auditList(@Valid AdminAuditQuery query) {
        return Result.success(adminService.auditList(query));
    }

    @Operation(summary = "资质申请详情",
            description = "比列表项多返回驳回原因、内部备注与申请说明。"
                    + "身份证号一律只返回脱敏串 —— 核验靠人工看证件照片，"
                    + "避免为「查看完整身份证号」再开一条难以审计的敏感通道")
    @GetMapping("/companion/audit/{id}")
    public Result<AuditApplicationVO> auditDetail(
            @Parameter(description = "申请 ID", example = "501") @PathVariable("id") Long id) {
        return Result.success(adminService.auditDetail(id));
    }

    @Operation(summary = "审核陪诊员资质",
            description = "驳回必须填原因（否则 8003），已是终态不可再审（否则 8001）。"
                    + "通过时把账号角色升级为陪诊员并使旧令牌失效（需重新登录），"
                    + "驳回时角色不变。两种结果都发站内信并写操作日志")
    @PostMapping("/companion/audit/{id}")
    public Result<AuditDecisionResultVO> decideAudit(
            @Parameter(description = "申请 ID", example = "501") @PathVariable("id") Long id,
            @Valid @RequestBody AuditDecisionDTO dto) {
        AuditDecisionResultVO result = adminService.decideAudit(id, dto);
        return Result.success(Boolean.TRUE.equals(dto.getApproved()) ? "已通过" : "已驳回", result);
    }

    /* ==================== 用户管理 ==================== */

    @Operation(summary = "用户列表",
            description = "支持按角色 / 状态 / 关键字（用户名·昵称·姓名·手机号）/ 注册时间筛选，"
                    + "附每个用户的关联订单数。手机号脱敏返回")
    @GetMapping("/user")
    public Result<PageResult<AdminUserVO>> userList(@Valid AdminUserQuery query) {
        return Result.success(adminService.userList(query));
    }

    @Operation(summary = "封禁用户",
            description = "封禁后该用户已签发的令牌立即失效（任意接口请求返回 403，不必等令牌过期）。"
                    + "封禁管理员返回 8002，重复封禁返回 2004")
    @PostMapping("/user/{id}/disable")
    public Result<UserStatusResultVO> disableUser(
            @Parameter(description = "用户 ID", example = "10099") @PathVariable("id") Long id,
            @Valid @RequestBody UserDisableDTO dto) {
        return Result.success("已封禁该用户", adminService.disableUser(id, dto));
    }

    @Operation(summary = "解封用户",
            description = "恢复账号状态并清除封禁标记；旧令牌不会自动恢复，用户需重新登录")
    @PostMapping("/user/{id}/enable")
    public Result<UserStatusResultVO> enableUser(
            @Parameter(description = "用户 ID", example = "10099") @PathVariable("id") Long id,
            @Valid @RequestBody UserEnableDTO dto) {
        return Result.success("已解封该用户", adminService.enableUser(id, dto));
    }

    @Operation(summary = "重置密码",
            description = "重置为默认密码并置「需强制改密」，同时使旧令牌立即失效。"
                    + "响应返回默认密码供管理员电话告知用户；绝不返回原密码（库里是 BCrypt，本就不可逆）")
    @PostMapping("/user/{id}/reset-password")
    public Result<ResetPasswordResultVO> resetPassword(
            @Parameter(description = "用户 ID", example = "10099") @PathVariable("id") Long id,
            @Valid @RequestBody ResetPasswordDTO dto) {
        return Result.success("密码已重置为默认密码，请提醒用户首次登录后修改",
                adminService.resetPassword(id, dto));
    }

    /* ==================== 订单管理 ==================== */

    @Operation(summary = "全部订单",
            description = "支持按状态（可多值逗号分隔）/ 关键字 / 下单时间区间筛选，"
                    + "hasComplaint=true 只看有投诉的订单。列表项附 hasComplaint 与最新投诉 ID")
    @GetMapping("/order")
    public Result<PageResult<AdminOrderVO>> orderList(@Valid AdminOrderQuery query) {
        return Result.success(adminService.orderList(query));
    }

    @Operation(summary = "纠纷处理",
            description = "强制把订单置为终态（只能 COMPLETED / CANCELLED），"
                    + "写 order_status_log 并标注「管理员强制变更」，同时写操作日志、"
                    + "给家属与陪诊员各发一条站内信。已是终态返回 3002")
    @PostMapping("/order/{id}/arbitrate")
    public Result<ArbitrateResultVO> arbitrate(
            @Parameter(description = "订单 ID", example = "1001") @PathVariable("id") Long id,
            @Valid @RequestBody ArbitrateDTO dto) {
        return Result.success("纠纷已处理", adminService.arbitrate(id, dto));
    }

    /* ==================== 投诉管理 ==================== */

    @Operation(summary = "投诉列表",
            description = "管理端可见全部投诉（不做收窄）；待处理状态优先排在前面")
    @GetMapping("/complaint")
    public Result<PageResult<ComplaintVO>> complaintList(@Valid AdminComplaintQuery query) {
        return Result.success(adminService.complaintList(query));
    }

    @Operation(summary = "处理投诉",
            description = "状态只能正向流转：PENDING → PROCESSING → RESOLVED / REJECTED，"
                    + "回退或跳级返回 409。进入终态时向投诉人与被投诉人各发一条站内信")
    @PostMapping("/complaint/{id}/handle")
    public Result<ComplaintHandleResultVO> handleComplaint(
            @Parameter(description = "投诉 ID", example = "3001") @PathVariable("id") Long id,
            @Valid @RequestBody ComplaintHandleDTO dto) {
        return Result.success("投诉已处理", adminService.handleComplaint(id, dto));
    }

    /* ==================== 操作日志 ==================== */

    @Operation(summary = "操作日志查询",
            description = "支持按操作人 / 操作类型 / 目标对象 / 操作时间区间（精确到秒）筛选。"
                    + "日志只增不改不删，本模块不提供任何修改或删除接口")
    @GetMapping("/oper-log")
    public Result<PageResult<OperLogVO>> operLogList(@Valid OperLogQuery query) {
        return Result.success(adminService.operLogList(query));
    }
}

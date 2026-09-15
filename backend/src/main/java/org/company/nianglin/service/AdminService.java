package org.company.nianglin.service;

import org.company.nianglin.common.PageResult;
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
import org.company.nianglin.entity.CompanionOrder;
import org.company.nianglin.entity.SysUser;
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

import java.util.List;

/**
 * 管理后台服务。
 *
 * <p>对应 {@code docs/api/08-admin.md} §1 ~ §12。</p>
 *
 * <h3>本模块的三条硬性约束（来自文档顶部）</h3>
 *
 * <ol>
 *   <li>仅 {@code ADMIN} 可访问 —— 由 {@code AdminController} 上的
 *       {@code @PreAuthorize} 一次性收口，不在每个方法里重复判断；</li>
 *   <li><b>每一次写操作都必须写 {@code admin_oper_log}</b>，
 *       做到「谁 / 何时 / 对谁 / 做了什么」可追溯。
 *       本类的每个写方法最后一步都是落日志，没有例外；</li>
 *   <li>只有管理员可通过「纠纷处理」强制改变订单终态 ——
 *       实现上走 {@code OrderService.forceTerminal}，状态写入权仍留在订单模块。</li>
 * </ol>
 *
 * <h3>为什么日志写入不抽成 AOP 注解</h3>
 *
 * <p>切面靠方法名或注解推断「操作类型 / 目标 / 变更前后状态」，而这三项
 * 在管理端恰恰是<b>业务语义</b>而不是技术属性：封禁时要记 {@code NORMAL → DISABLED}，
 * 纠纷处理要记 {@code IN_SERVICE → CANCELLED}，这些值只有业务代码知道。
 * 用切面就得在注解里再写一遍表达式，比直接调用更容易出错，
 * 而且日志会写在业务方法<b>之外</b>，一旦业务方法抛异常，
 * AOP 的 {@code @AfterReturning} 不会记录 —— 而「尝试封禁但失败」
 * 恰恰是审计最关心的一类事件。因此这里显式写日志。</p>
 *
 * @author 银龄伴诊团队
 * @since M9
 */
public interface AdminService {

    /* ==================== 资质审核 ==================== */

    /** 资质申请列表（额外返回三种状态的数量，不受分页与筛选影响） */
    AuditPageVO auditList(AdminAuditQuery query);

    /** 资质申请详情（含驳回原因、内部备注与申请说明；身份证仍只出脱敏串） */
    AuditApplicationVO auditDetail(Long id);

    /**
     * 审核资质（通过 / 驳回）。
     *
     * <p>通过时把用户角色升级为 {@code COMPANION} 并把快照置为可接单；
     * 驳回时角色不变、驳回原因落库。两种结果都向申请人发站内信并写操作日志。</p>
     *
     * @throws org.company.nianglin.exception.BusinessException 2007 申请不存在 / 8001 已是终态 / 8003 驳回未填原因
     */
    AuditDecisionResultVO decideAudit(Long id, AuditDecisionDTO dto);

    /* ==================== 用户管理 ==================== */

    /** 用户列表（手机号脱敏，附关联订单数） */
    PageResult<AdminUserVO> userList(AdminUserQuery query);

    /**
     * 封禁用户。
     *
     * <p>封禁后该用户<b>已签发的令牌立即失效</b>：除改库状态外，
     * 还会在 Redis 打封禁标记（由 {@code JwtAuthenticationFilter} 就地返回 403）
     * 并递增密码版本作为双保险。</p>
     *
     * @throws org.company.nianglin.exception.BusinessException 2004 用户不存在或已封禁 / 8002 不能封禁管理员
     */
    UserStatusResultVO disableUser(Long id, UserDisableDTO dto);

    /** 解封用户（幂等之外的重复调用返回 2004，避免日志里出现无意义的空操作记录） */
    UserStatusResultVO enableUser(Long id, UserEnableDTO dto);

    /**
     * 重置密码为默认密码，并置 {@code need_change_password = 1}、使旧令牌立即失效。
     *
     * @throws org.company.nianglin.exception.BusinessException 2004 用户不存在
     */
    ResetPasswordResultVO resetPassword(Long id, ResetPasswordDTO dto);

    /* ==================== 订单管理 ==================== */

    /** 全部订单（附 {@code hasComplaint} 与最新投诉 ID，便于纠纷优先处理） */
    PageResult<AdminOrderVO> orderList(AdminOrderQuery query);

    /**
     * 纠纷处理：强制把订单置为终态，并向家属与陪诊员各发一条站内信。
     *
     * @throws org.company.nianglin.exception.BusinessException 3001 订单不存在 / 3002 已是终态 / 400 目标状态不合法
     */
    ArbitrateResultVO arbitrate(Long id, ArbitrateDTO dto);

    /* ==================== 投诉管理 ==================== */

    /** 投诉列表（管理端可见全部，不做收窄） */
    PageResult<ComplaintVO> complaintList(AdminComplaintQuery query);

    /**
     * 处理投诉（只可正向流转：{@code PENDING → PROCESSING → RESOLVED / REJECTED}）。
     *
     * @throws org.company.nianglin.exception.BusinessException 6004 不存在 / 409 非法流转
     */
    ComplaintHandleResultVO handleComplaint(Long id, ComplaintHandleDTO dto);

    /* ==================== 操作日志 ==================== */

    /** 操作日志查询（只读，不提供任何修改与删除入口） */
    PageResult<OperLogVO> operLogList(OperLogQuery query);

    /* ==================== 供 M10 导出复用 ==================== */

    /**
     * 用与 {@link #orderList(AdminOrderQuery)} <b>完全相同的筛选条件</b>
     * 取出订单实体，供 Excel 导出使用。
     *
     * <p>导出接口的验收项是「导出的数据必须与页面上当前筛选结果一致」。
     * 若导出另写一套筛选，两边早晚会出现差异 —— 而且差异通常只在
     * 某个特定组合条件（比如「只看有投诉的已取消订单」）下才暴露，
     * 属于最难发现的一类 bug。因此筛选逻辑只有一个实现，
     * 导出的差别仅在「不分页、有行数上限」。</p>
     *
     * @param limit 行数上限；内部会多查一行人用于判断是否超限
     * @return 订单实体列表，最多 {@code limit + 1} 条
     */
    List<CompanionOrder> queryOrdersForExport(AdminOrderQuery query, int limit);

    /**
     * 用与 {@link #userList(AdminUserQuery)} 相同的筛选条件取用户实体，供导出使用。
     *
     * @param limit 行数上限；内部会多查一行人用于判断是否超限
     */
    List<SysUser> queryUsersForExport(AdminUserQuery query, int limit);
}

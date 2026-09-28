package org.company.nianglin.service.support;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.company.nianglin.common.ResultCode;
import org.company.nianglin.constant.OrderStatus;
import org.company.nianglin.constant.RoleConstants;
import org.company.nianglin.entity.CompanionOrder;
import org.company.nianglin.entity.CompanionProfile;
import org.company.nianglin.entity.OrderStatusLog;
import org.company.nianglin.entity.SysUser;
import org.company.nianglin.exception.BusinessException;
import org.company.nianglin.mapper.CompanionOrderMapper;
import org.company.nianglin.mapper.CompanionProfileMapper;
import org.company.nianglin.mapper.OrderStatusLogMapper;
import org.company.nianglin.mapper.SysUserMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单状态流转单一入口实现（ADR-0010）。
 *
 * <p><b>本类是全项目唯一允许调用 {@link OrderStatus#canTransitTo} 的地方。</b>
 * 收口前该方法在生产代码里零调用，`OrderServiceImpl` 各自硬编码
 * {@code from != X} 守卫共 4 份，判定规则可以被静默破坏而测试全绿。
 * 收口后删 {@code TRANSITIONS} 任一条边，本类的判定即改变行为，
 * 相应测试会失败（ADR-0010 验证标准第 4 条）。</p>
 *
 * @author 银龄伴诊团队
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderTransitionServiceImpl implements OrderTransitionService {

    private final CompanionOrderMapper orderMapper;
    private final OrderStatusLogMapper statusLogMapper;
    private final CompanionProfileMapper companionProfileMapper;
    private final SysUserMapper sysUserMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void transition(CompanionOrder order, OrderStatus target, Operator operator, String remark) {
        if (order == null) {
            throw new BusinessException(ResultCode.ORDER_NOT_FOUND);
        }
        if (target == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "目标状态不能为空");
        }

        OrderStatus from = OrderStatus.of(order.getStatus());
        if (from == null) {
            // 库里出现转移表之外的状态 = 数据已被绕过入口的路径写脏，
            // 此时任何「合法流转」判断都不可信，直接拒绝而不是猜测。
            throw new BusinessException(ResultCode.ORDER_STATUS_ILLEGAL, "订单状态非法，无法流转");
        }

        requireRoleAllowed(target, operator);
        requireLegalTransition(from, target);

        order.setStatus(target.name());
        // 乐观锁：MyBatis-Plus 生成 WHERE id=? AND version=?，
        // 50 并发抢单只有第 1 个 affectedRows=1，其余为 0。
        int rows = orderMapper.updateById(order);
        if (rows == 0) {
            log.info("状态流转乐观锁未命中（已被抢先） | orderId={} | from={} | to={}",
                    order.getId(), from, target);
            // 并发落败时回什么码，取决于这条流转的业务语义：
            // 接单被抢先 = 3003（单子已被别人接走），取消被抢先 = 3006（已不在待接单）。
            // 统一回 3003 会让「取消失败」被误读成「被抢单」，所以由调用方指定。
            throw new BusinessException(lockConflictCode(target));
        }

        writeLog(order.getId(), from, target, operator, remark, 0);
        log.info("订单状态流转 | orderId={} | {} -> {} | operator={}",
                order.getId(), from, target, operator.userId());
    }

    @Override
    public void requireTransitionAllowed(OrderStatus from, OrderStatus target, Operator operator) {
        if (from == null) {
            throw new BusinessException(ResultCode.ORDER_STATUS_ILLEGAL, "订单状态非法，无法流转");
        }
        requireRoleAllowed(target, operator);
        requireLegalTransition(from, target);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void forceTerminal(CompanionOrder order, OrderStatus target, Long adminId, String remark) {
        if (target == null || !target.isAdminForceable()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "只能强制至「已完成」或「已取消」");
        }
        if (order == null) {
            throw new BusinessException(ResultCode.ORDER_NOT_FOUND);
        }
        OrderStatus current = OrderStatus.of(order.getStatus());
        if (current == null) {
            throw new BusinessException(ResultCode.ORDER_STATUS_ILLEGAL);
        }
        if (current.isTerminal()) {
            // 终态对管理员强制同样关闭 —— 与 AGENTS.md §4.1「终态任何路径不得变更」一致
            throw new BusinessException(ResultCode.ORDER_STATUS_ILLEGAL, "该订单已处于终态，不可再变");
        }
        if (current == target) {
            throw new BusinessException(ResultCode.ORDER_STATUS_ILLEGAL);
        }

        order.setStatus(target.name());
        int rows = orderMapper.updateById(order);
        if (rows == 0) {
            throw new BusinessException(ResultCode.CONFLICT, "订单状态已变更，请刷新后重试");
        }

        writeLog(order.getId(), current, target, Operator.of(adminId, RoleConstants.ADMIN, false),
                remark, 1);
        log.info("管理员强制终态 | orderId={} | {} -> {} | adminId={}", order.getId(), current, target, adminId);
    }

    /**
     * 角色门禁：谁可以触发哪个目标状态。
     *
     * <p>AGENTS.md §4.3 的硬约束「老人账号写操作一律 403」在这里落地 ——
     * 放在入口层而不是各 Controller，才不会被新写的端点漏掉。</p>
     */
    private void requireRoleAllowed(OrderStatus target, Operator operator) {
        if (operator == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        if (operator.system()) {
            return;
        }
        if (RoleConstants.ELDER.equals(operator.role())) {
            throw new BusinessException(ResultCode.FORBIDDEN, "老人账号不可执行写操作");
        }
        if (operator.userId() == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        // 目标为已取消时，只允许家属单方取消或管理员强制（后者走 forceTerminal）
        if (target == OrderStatus.CANCELLED && !RoleConstants.FAMILY.equals(operator.role())) {
            throw new BusinessException(ResultCode.ORDER_CANNOT_CANCEL);
        }
    }

    /**
     * 合法流转判定 —— <b>全项目唯一调用 {@code canTransitTo} 的位置</b>。
     *
     * <p>家属取消是独立于正向表的合法路径：{@code TRANSITIONS} 只承载正向流转
     * （见 {@link OrderStatus} 类注释），所以 {@code PENDING → CANCELLED}
     * 必须在此显式放行，而不是往表里塞一条边 —— 否则
     * 「仅管理员可强制进入 CANCELLED」这层语义就丢了。</p>
     */
    private void requireLegalTransition(OrderStatus from, OrderStatus target) {
        if (target == OrderStatus.CANCELLED) {
            // 取消路径优先判定：已取消的订单再次取消要回 3006（不可取消），
            // 而不是被下面的终态守卫拦成 3002 —— 两者对家属是完全不同的提示。
            // 家属看到 3006 才知道「这单已经取消过了」。
            if (from != OrderStatus.PENDING) {
                // 已接单之后不能再由家属单方面取消 —— 陪诊员可能已经在路上，
                // 这种情况要走 M9 的纠纷处理
                throw new BusinessException(ResultCode.ORDER_CANNOT_CANCEL);
            }
            return;
        }
        if (from.isTerminal()) {
            throw new BusinessException(ResultCode.ORDER_STATUS_ILLEGAL,
                    "「" + from.getLabel() + "」是终态，不可再流转");
        }
        if (from == target) {
            throw new BusinessException(ResultCode.ORDER_STATUS_ILLEGAL);
        }
        if (!from.canTransitTo(target)) {
            // ★ 权威判定：转移表说不行就是不行，跳级 / 回退在此被拦下
            throw new BusinessException(ResultCode.ORDER_STATUS_ILLEGAL);
        }
    }

    /** 乐观锁落败时的业务码：取消回 3006，其余（接单等）回 3003 */
    private ResultCode lockConflictCode(OrderStatus target) {
        return target == OrderStatus.CANCELLED
                ? ResultCode.ORDER_CANNOT_CANCEL
                : ResultCode.ORDER_ALREADY_TAKEN;
    }

    /** 流转日志双写：与订单更新同事务，异常一并回滚 */
    private void writeLog(Long orderId, OrderStatus from, OrderStatus to,
                          Operator operator, String remark, int isForce) {
        OrderStatusLog row = new OrderStatusLog();
        row.setOrderId(orderId);
        row.setFromStatus(from == null ? null : from.name());
        row.setToStatus(to.name());
        row.setOperatorId(operator == null ? null : operator.userId());
        row.setOperatorName(operator == null ? null : resolveOperatorName(operator));
        row.setOperatorRole(operator == null || operator.system()
                ? RoleConstants.SYSTEM : operator.role());
        row.setRemark(remark);
        row.setIsForce(isForce);
        row.setOperateTime(LocalDateTime.now());
        statusLogMapper.insert(row);
    }

    /**
     * 操作人显示名：陪诊员取资质快照里的真实姓名，其余取昵称，都没有再退到用户名。
     *
     * <p>在这里定格成快照，之后操作人改名不会篡改历史记录。</p>
     */
    private String resolveOperatorName(Operator operator) {
        if (RoleConstants.COMPANION.equals(operator.role())) {
            // 与 OrderServiceImpl#findCompanionProfile 保持同一套查询语义
            // （按 userId 查资质快照，取最早一条），避免日志里的姓名与其它地方对不上
            List<CompanionProfile> list = companionProfileMapper.selectList(
                    Wrappers.<CompanionProfile>lambdaQuery()
                            .eq(CompanionProfile::getUserId, operator.userId())
                            .orderByAsc(CompanionProfile::getId));
            if (list != null && !list.isEmpty() && StringUtils.hasText(list.get(0).getRealName())) {
                return list.get(0).getRealName();
            }
        }
        SysUser user = sysUserMapper.selectById(operator.userId());
        if (user == null) {
            return null;
        }
        if (StringUtils.hasText(user.getRealName())) {
            return user.getRealName();
        }
        return user.getNickname();
    }
}

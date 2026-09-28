package org.company.nianglin.service.support;

import org.company.nianglin.constant.OrderStatus;
import org.company.nianglin.entity.CompanionOrder;

/**
 * 订单状态流转的<b>唯一入口</b>（ADR-0010）。
 *
 * <p>{@code OrderStatus.canTransitTo} 是流转规则的唯一权威，但权威本身不构成保护 ——
 * 只要业务代码能绕开它，转移表就只是一份没人读的文档。本接口把
 * 「合法流转判定 / 角色门禁 / 乐观锁落库 / 流转日志双写」四项
 * <b>结构性绑定</b>在一起：想跳过规则，就必须绕开整个 service，
 * 而那在 code review 里极其显眼。</p>
 *
 * <p><b>业务 Service 的义务</b>（AGENTS.md §4.1）：</p>
 * <ul>
 *   <li>不得 {@code order.setStatus(...)} 改状态；</li>
 *   <li>不得用 {@code orderMapper.updateById(order)} 改状态；</li>
 *   <li>需要改状态时只能调 {@link #transition}。</li>
 * </ul>
 *
 * @author 银龄伴诊团队
 * @see OrderStatus#canTransitTo(OrderStatus)
 */
public interface OrderTransitionService {

    /**
     * 执行一次状态流转，并把日志与订单更新放在同一事务里。
     *
     * @param order    订单实体（方法内会被改状态并落库，调用方不要再自行 update）
     * @param target   目标状态
     * @param operator 操作人上下文
     * @param remark   流转备注，写入状态日志
     * @throws org.company.nianglin.exception.BusinessException 流转不合法时抛出，事务回滚
     */
    void transition(CompanionOrder order, OrderStatus target, Operator operator, String remark);

    /**
     * 只判定、不落库 —— 供需要「先判状态再判归属」的调用方在写操作之前预检。
     *
     * <p>存在的理由是一个具体的体验问题：待接单的订单根本没有陪诊员，
     * 若先判归属会返回 4003「你不是本单陪诊员」，而用户真正需要知道的是
     * 3002「订单还没被接单」。因此某些方法必须先把状态问一遍。
     * 预检与真正落库走的是<b>同一份判定</b>，不会出现两套规则。</p>
     *
     * @param from  当前状态
     * @param target 目标状态
     * @param operator 操作人上下文
     */
    void requireTransitionAllowed(OrderStatus from, OrderStatus target, Operator operator);

    /**
     * 管理员强制改变终态（M9 纠纷处理）。
     *
     * <p>这是<b>明确豁免</b>状态机的独立路径：由 {@link OrderStatus#isAdminForceable()} +
     * {@link OrderStatus#isTerminal()} 承担判定，<b>不得</b>塞进
     * {@code TRANSITIONS}，否则「仅管理员可强制」这一层语义就丢了。</p>
     *
     * @param order   订单实体
     * @param target  目标状态，只允许 COMPLETED / CANCELLED
     * @param adminId 操作管理员 id
     * @param remark  强制原因，写入状态日志的 isForce=1
     */
    void forceTerminal(CompanionOrder order, OrderStatus target, Long adminId, String remark);

    /**
     * 操作人上下文。
     *
     * @param userId  操作人 id
     * @param role    操作人角色
     * @param system  是否系统触发（定时任务等）
     */
    record Operator(Long userId, String role, boolean system) {

        public static Operator of(Long userId, String role, boolean system) {
            return new Operator(userId, role, system);
        }
    }
}

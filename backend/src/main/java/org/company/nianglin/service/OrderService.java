package org.company.nianglin.service;

import org.company.nianglin.common.PageResult;
import org.company.nianglin.dto.OrderCancelDTO;
import org.company.nianglin.dto.OrderCompleteDTO;
import org.company.nianglin.dto.OrderCreateDTO;
import org.company.nianglin.dto.OrderHallQuery;
import org.company.nianglin.dto.OrderQuery;
import org.company.nianglin.dto.OrderRejectDTO;
import org.company.nianglin.entity.CompanionOrder;
import org.company.nianglin.vo.OrderAcceptResultVO;
import org.company.nianglin.vo.OrderCreateResultVO;
import org.company.nianglin.vo.OrderFlowResultVO;
import org.company.nianglin.vo.OrderTimelineVO;
import org.company.nianglin.vo.OrderVO;

import java.util.List;

/**
 * 陪诊订单服务。
 *
 * <p>对应 {@code docs/api/03-order.md} §1 ~ §10，实现约束见该文档「状态机」一节。</p>
 *
 * <h3>本模块的两条主线</h3>
 *
 * <ol>
 *   <li><b>状态机只能沿着 PENDING → ACCEPTED → IN_SERVICE → COMPLETED → REVIEWED 前进</b>。
 *       跳级、回退、终态再流转一律 {@code 3002}。判断只允许走
 *       {@code OrderStatus} 枚举，禁止硬编码字符串。</li>
 *   <li><b>状态与"谁在操作"必须同时成立</b>。角色注解只回答「你是不是陪诊员」，
 *       回答不了「这一单是不是你的」——后者由
 *       {@link #requireInvolved(Long)} 与各写方法内部的归属判断承担（{@code 3004} / {@code 4003}）。</li>
 * </ol>
 *
 * <h3>并发下的写入策略（验收项，改动前先读）</h3>
 *
 * <p>「接单」必须用乐观锁：两个陪诊员同时点接单，先读出同一个 {@code version}，
 * 谁先写谁成功，后写的 {@code affectedRows = 0} → {@code 3003}。
 * 其余流转（取消 / 开始 / 完成）用
 * {@code UPDATE ... WHERE id = ? AND status = '期望状态'} 的条件更新，
 * 同样是原子的，只是不需要区分「被谁抢先」，统一回 {@code 3002} 即可。</p>
 *
 * <p>两种写法都<b>不允许</b>退化成「先 SELECT 判断、再按 id 无条件 UPDATE」，
 * 那在并发下必然写出错误状态。</p>
 *
 * @author 银龄伴诊团队
 * @since M4
 */
public interface OrderService {

    /** 创建订单（FAMILY，老人须在自己名下），返回订单号与初始状态 */
    OrderCreateResultVO create(OrderCreateDTO dto);

    /** 我的订单列表：按当前角色自动决定数据范围（家属看自己下的、陪诊员看自己接的、老人看自己就诊的） */
    PageResult<OrderVO> myOrders(OrderQuery query);

    /** 待接单订单大厅（COMPANION 且资质已通过；只含未过期的 PENDING 单，并排除自己已拒过的） */
    PageResult<OrderVO> hall(OrderHallQuery query);

    /** 订单详情（须为相关方） */
    OrderVO detail(Long orderId);

    /** 取消订单（FAMILY 本人，仅 PENDING 可取消） */
    void cancel(Long orderId, OrderCancelDTO dto);

    /** 接单（COMPANION 且资质已通过；乐观锁防超卖，被抢返回 3003） */
    OrderAcceptResultVO accept(Long orderId);

    /** 拒单（不影响订单状态，仅让本单不再出现在该陪诊员的大厅里） */
    void reject(Long orderId, OrderRejectDTO dto);

    /** 开始服务（须为本单陪诊员，状态须为 ACCEPTED） */
    OrderFlowResultVO start(Long orderId);

    /** 完成服务（须为本单陪诊员，状态须为 IN_SERVICE；服务小结过合规校验） */
    OrderFlowResultVO complete(Long orderId, OrderCompleteDTO dto);

    /** 状态流转时间线（须为相关方） */
    List<OrderTimelineVO> timeline(Long orderId);

    /**
     * 校验当前登录用户是否为该订单的相关方，是则返回订单实体。
     *
     * <p>这是跨模块复用的入口：{@code ADMIN} 直通；
     * {@code FAMILY} 须为下单人；{@code COMPANION} 须为接单人；
     * {@code ELDER} 须为就诊人本人；其余一律 {@code 3004}。</p>
     *
     * <p>M5（打卡 / 轨迹 / 实时进度）、M7（评价）都要判断「这一单跟我有没有关系」，
     * 一律复用本方法，不要各写一套 —— 三套归属判断里最松的那一套就是漏洞。</p>
     *
     * <p>⚠️ 返回的是实体。调用方不得直接返回给前端，必须转 VO。</p>
     *
     * @param orderId 订单 ID
     * @return 订单实体（非 null）
     * @throws org.company.nianglin.exception.BusinessException 3001 不存在 / 3004 非相关方
     */
    CompanionOrder requireInvolved(Long orderId);
}

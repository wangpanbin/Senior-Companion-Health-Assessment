package org.company.nianglin.service;

import org.company.nianglin.common.PageResult;
import org.company.nianglin.constant.OrderStatus;
import org.company.nianglin.dto.OrderCancelDTO;
import org.company.nianglin.dto.OrderCompleteDTO;
import org.company.nianglin.dto.OrderCreateDTO;
import org.company.nianglin.dto.OrderHallQuery;
import org.company.nianglin.dto.OrderQuery;
import org.company.nianglin.dto.OrderRejectDTO;
import org.company.nianglin.entity.CompanionOrder;
import org.company.nianglin.security.LoginUser;
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
     * 把订单从 {@code COMPLETED} 推进到 {@code REVIEWED}（供 M7 评价成功后调用）。
     *
     * <p>状态机允许 {@code COMPLETED → REVIEWED} 这条正向流转，
     * 但「评价」这件事本身不属于订单模块，所以由 M7 校验完「订单已完成、且没评过」
     * 之后再来推进状态。<b>订单状态的写入权始终留在本模块</b>，
     * 不允许 M7 直接 UPDATE {@code companion_order.status} ——
     * 那样一来订单状态机就有两个写入口，日后加一条流转规则必然漏改一边。</p>
     *
     * <p>实现用「条件更新 + 状态日志」：条件更新保证并发下只有一个请求完成推进，
     * 日志让订单时间线里能出现「已评价」这个节点（否则家属会看到
     * 订单停在「已完成」而评价却已经提交了）。</p>
     *
     * <p>幂等：若订单已经是 {@code REVIEWED}，本方法直接返回，不抛异常，
     * 也不重复写日志。</p>
     *
     * @param orderId 订单 ID
     * @throws org.company.nianglin.exception.BusinessException 3001 订单不存在
     */
    void markReviewed(Long orderId);

    /**
     * 管理员强制把订单置为终态（供 M9 纠纷处理调用）。
     *
     * <p>这是状态机<b>唯一的越权入口</b>，也是它唯一被允许绕过正向流转的场景：
     * 陪诊员迟到、家属拒付、双方各执一词时，平台必须有能力一次性结束争议，
     * 而不是留下一个永远停在「服务中」的订单。</p>
     *
     * <p>三条约束缺一不可：</p>
     * <ol>
     *   <li>目标状态只能是 {@code COMPLETED} / {@code CANCELLED}
     *       （{@code OrderStatus.isAdminForceable()}），不允许改回中间态；</li>
     *   <li>已是终态的订单不再处理，返回 {@code 3002}；</li>
     *   <li>必须写 {@code order_status_log} 并标注「管理员强制变更」——
     *       否则订单时间线里会凭空出现一次状态跳变，事后谁也说不清是谁改的。</li>
     * </ol>
     *
     * <p><b>写权留在订单模块</b>：M9 不直接 UPDATE {@code companion_order.status}，
     * 与 {@link #markReviewed} 同一个理由 —— 状态机不允许有两个写入口。</p>
     *
     * @param orderId 订单 ID
     * @param target  目标终态，只能是 {@code COMPLETED} 或 {@code CANCELLED}
     * @param remark  处理结果说明，写入状态日志
     * @return 变更后的订单实体（调用方不得直接返回给前端，必须转 VO）
     * @throws org.company.nianglin.exception.BusinessException
     *         3001 订单不存在 / 3002 已终态或并发冲突 / 400 目标状态不合法
     */
    CompanionOrder forceTerminal(Long orderId, OrderStatus target, String remark);

    /**
     * 校验当前登录用户是否为该订单的相关方，是则返回订单实体。
     *
     * <p>这是跨模块复用的入口：{@code ADMIN} 直通；
     * {@code FAMILY} 须为下单人；{@code COMPANION} 须为接单人；
     * {@code ELDER} 须为就诊人本人；其余一律 {@code 3004}。</p>
     *
     * <p>M5（打卡 / 轨迹 / 实时进度）、M7（评价）、M8（站内信）都要判断
     * 「这一单跟我有没有关系」，一律复用本方法，不要各写一套 ——
     * 三套归属判断里最松的那一套就是漏洞。</p>
     *
     * <p>⚠️ 返回的是实体。调用方不得直接返回给前端，必须转 VO。</p>
     *
     * @param orderId 订单 ID
     * @return 订单实体（非 null）
     * @throws org.company.nianglin.exception.BusinessException 3001 不存在 / 3004 非相关方
     */
    CompanionOrder requireInvolved(Long orderId);

    /**
     * 同 {@link #requireInvolved(Long)}，但<b>身份由调用方显式给出</b>，不读
     * {@code SecurityContext}。
     *
     * <p>存在的唯一理由是 WebSocket：握手是一次普通 HTTP 请求，
     * 但会话建立后，帧处理跑在别的线程上，{@code SecurityContextHolder}
     * 早已被清空，{@link org.company.nianglin.security.SecurityUtils#currentUser()}
     * 在那里拿不到人。握手拦截器手上有解析好的令牌载荷，
     * 于是用这个重载把身份直接传进来。</p>
     *
     * <p><b>不要</b>在 REST 接口里用它 —— 参数化的身份等于把归属判断交给调用方，
     * 一旦有人传错，注解和过滤器的保护全部作废。</p>
     *
     * @param orderId   订单 ID
     * @param loginUser 身份，不得为 {@code null}
     * @return 订单实体（非 null）
     */
    CompanionOrder requireInvolved(Long orderId, LoginUser loginUser);
}

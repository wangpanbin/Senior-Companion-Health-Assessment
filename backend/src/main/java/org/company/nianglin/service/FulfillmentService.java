package org.company.nianglin.service;

import org.company.nianglin.constant.FulfillmentRisk;
import org.company.nianglin.dto.OrderUrgeDTO;
import org.company.nianglin.vo.OrderUrgeResultVO;

/**
 * 履约保障服务（V3.1）。
 *
 * <p>回答的是订单状态机之外的那一类问题：「这笔单还接不接得成」。
 * 它<b>不改订单状态</b> —— 状态只由 {@code OrderTransitionService} 驱动，
 * 履约风险是搭在旁边的独立标记（见 {@link FulfillmentRisk}）。</p>
 *
 * @author 银龄伴诊团队
 * @since V3.1
 */
public interface FulfillmentService {

    /**
     * 扫描待接单订单并按等待时长升级履约风险（定时任务入口，无登录上下文）。
     *
     * <p><b>幂等</b>：同一批订单连扫两次，第二次不会产生任何新事件与通知。
     * 做法是「按读到的旧风险等级认领」——条件更新要求库里此刻仍是我读到的那一档，
     * 被别人抢走（rows=0）就直接跳过。</p>
     *
     * <p><b>只升不降</b>：等级只前进。回落只由「订单离开 PENDING」触发。</p>
     *
     * @return 本轮实际升级的订单数
     */
    int scanAndEscalate();

    /**
     * 订单创建后登记履约事件（家属下单时调用，与建单同事务）。
     *
     * <p>此时风险恒为 {@link FulfillmentRisk#NORMAL}，事件只用于让家属与管理员
     * 能看到「这笔单从什么时候开始等」。</p>
     *
     * @param orderId 订单 ID
     * @param familyId 下单家属用户 ID
     * @param orderNo  订单号（用于通知文案）
     */
    void registerCreated(Long orderId, Long familyId, String orderNo);

    /**
     * 家属催单（V3.1 · T3）。
     *
     * <p>仅在本单下单人、订单仍为 {@code PENDING}、且履约风险已达「较高」时开放，
     * 并受冷却期与次数上限约束。</p>
     *
     * <p><b>资格判定与自增合并在一条条件更新里</b>：不做「先查后写」。
     * 两个并发请求会同时通过前置校验，先查后写会让它们都成功并把次数写成同一个值；
     * 条件更新让第二个请求影响 0 行，自然互斥。</p>
     *
     * @throws org.company.nianglin.exception.BusinessException 3001 订单不存在 / 3004 非下单人 /
     *                                                         3008 不可催 / 3009 过于频繁
     */
    OrderUrgeResultVO urge(Long orderId, OrderUrgeDTO dto);
}

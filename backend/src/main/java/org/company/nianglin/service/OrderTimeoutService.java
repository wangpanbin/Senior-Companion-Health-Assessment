package org.company.nianglin.service;

/**
 * 订单超时闭环（closure-2026-11 · P1-1）。
 *
 * <p>订单只有「有人在操作」时才会前进。本服务给订单补两个超时出口：</p>
 * <ol>
 *   <li><b>待接单超时</b>：就诊时间过去 N 小时仍无人接单 → 默认只标记
 *       （风险升到 {@code MANUAL_REQUIRED} + 写履约事件 + 通知家属），
 *       自动取消是独立开关且默认关闭；</li>
 *   <li><b>服务中超时</b>：进入服务中超过 N 小时仍未完成 → 只升级风险，
 *       <b>不改订单状态</b>（服务可能真的还在进行）。</li>
 * </ol>
 *
 * @author 银龄伴诊团队
 * @since closure-2026-11
 */
public interface OrderTimeoutService {

    /**
     * 扫描一轮订单超时。
     *
     * @return 本轮实际处理（标记 / 取消 / 升级）的订单数
     */
    int scanTimeouts();
}

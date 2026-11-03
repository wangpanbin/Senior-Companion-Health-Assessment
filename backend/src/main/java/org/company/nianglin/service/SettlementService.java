package org.company.nianglin.service;

import jakarta.servlet.http.HttpServletResponse;
import org.company.nianglin.common.PageResult;
import org.company.nianglin.dto.AdminSettlementQuery;
import org.company.nianglin.dto.SettlementBatchConfirmDTO;
import org.company.nianglin.vo.SettlementBatchConfirmVO;
import org.company.nianglin.vo.SettlementConfirmVO;
import org.company.nianglin.vo.SettlementOrderVO;

/**
 * 结算核对服务（V4 · P2-1 结算闭环）。
 *
 * <p>对应 {@code docs/api/08-admin.md} §25 ~ §27 与
 * {@code docs/api/09-statistics-export.md} §9。</p>
 *
 * <h3>职责边界</h3>
 *
 * <p>本服务是 {@code companion_order.payment_status} 从 {@code UNPAID}
 * 变成 {@code SETTLED} 的<b>唯一入口</b>。它只改记账状态，
 * 不改订单状态、不改接单人、不碰任何资金渠道 ——
 * 平台的合规口径是「线上记账 + 线下结算」，结算动作本身发生在平台之外，
 * 这里记录的是「核对结果」。</p>
 *
 * <h3>金额只读</h3>
 *
 * <p>所有金额都由服务端从订单当前的 {@code fee} / {@code actual_fee} 计算，
 * 接口不接收任何金额入参。核定的同时把 {@code actual_fee} 快照为
 * {@code COALESCE(actual_fee, fee)}，使「核定那一刻的结算金额」被冻结。</p>
 *
 * @author 银龄伴诊团队
 * @since V4 · P2-1
 */
public interface SettlementService {

    /**
     * 结算核对列表。
     *
     * <p>台账固定只收 {@code COMPLETED} / {@code REVIEWED}，
     * 周期按服务完成时间 {@code finish_time} 过滤；姓名一律脱敏。</p>
     */
    PageResult<SettlementOrderVO> list(AdminSettlementQuery query);

    /**
     * 单条核定。
     *
     * <p>幂等：已结算的订单重复调用返回成功且 {@code settledNow=false}，
     * 不产生第二条操作日志。</p>
     *
     * @throws org.company.nianglin.exception.BusinessException
     *         3001 订单不存在 · 8004 当前订单状态不可结算核对
     */
    SettlementConfirmVO confirm(Long orderId);

    /**
     * 批量核定。
     *
     * <p>入参为订单 ID 列表（去重保序）；逐项独立成败，
     * 业务失败不中断其余项；数据库异常整批回滚。
     * 每笔真正变更的订单各写一条操作日志。</p>
     */
    SettlementBatchConfirmVO batchConfirm(SettlementBatchConfirmDTO dto);

    /**
     * 导出结算单 Excel（字段口径与列表完全一致）。
     *
     * <p>行数超过上限抛 {@code 9001}；响应直接写二进制流，不经统一响应结构。</p>
     */
    void export(AdminSettlementQuery query, HttpServletResponse response);
}

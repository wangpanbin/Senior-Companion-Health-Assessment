package org.company.nianglin.service;

import org.company.nianglin.common.PageResult;
import org.company.nianglin.dto.VitalSignCreateDTO;
import org.company.nianglin.dto.VitalSignQuery;
import org.company.nianglin.dto.VitalTrendQuery;
import org.company.nianglin.vo.VitalSignVO;
import org.company.nianglin.vo.VitalTrendVO;
import org.company.nianglin.vo.VitalWithdrawVO;

/**
 * 体征记录服务（V4 · P2-2）。
 *
 * <h3>职责边界</h3>
 *
 * <p>只负责「保存测量数值 + 按时间展示」。<b>不做任何解释、分级或提醒</b>
 * （合规红线-1：只记录数值，不给出医学结论）。数值上下限只用于拦截误录，
 * 错误文案不带任何医学含义。</p>
 *
 * <h3>归属校验分两条链路</h3>
 *
 * <ul>
 *   <li>家属 / 老人：走 {@code ElderService#requireAccessible}；</li>
 *   <li>陪诊员：<b>不调用</b> {@code requireAccessible}（它对 {@code COMPANION} 抛 {@code 2006}），
 *       改走「订单存在 → 本单陪诊员 → 订单在服务中」，由订单自带的 {@code elderId} 定位老人。</li>
 * </ul>
 *
 * <p>对应 {@code docs/api/12-elder-health.md} §6 ~ §8。</p>
 *
 * @author 银龄伴诊团队
 * @since V4 · P2-2
 */
public interface VitalSignService {

    /** 家属：绑定老人的体征时间线（按测量时间倒序分页） */
    PageResult<VitalSignVO> listForFamily(Long elderId, VitalSignQuery query);

    /** 家属：绑定老人的体征趋势（按测量时间升序，最多 500 点） */
    VitalTrendVO trendForFamily(Long elderId, VitalTrendQuery query);

    /** 家属：代录一条体征 */
    VitalSignVO createForFamily(Long elderId, VitalSignCreateDTO dto);

    /** 家属：撤回本人代录的体征 */
    VitalWithdrawVO withdrawByFamily(Long recordId);

    /** 老人：本人体征时间线（档案由登录态推导） */
    PageResult<VitalSignVO> listForElder(VitalSignQuery query);

    /** 老人：本人体征趋势（档案由登录态推导） */
    VitalTrendVO trendForElder(VitalTrendQuery query);

    /**
     * 陪诊员：服务中为订单关联老人代录一条体征。
     *
     * <p>校验顺序固定：订单不存在 {@code 3001} → 非本单 {@code 3004} →
     * 非 {@code IN_SERVICE} {@code 3002} → 请求体校验 {@code 400}。</p>
     */
    VitalSignVO createForCompanion(Long orderId, VitalSignCreateDTO dto);

    /**
     * 陪诊员：撤回本单在服务中代录的体征。
     *
     * <p>订单校验同上；记录必须属于本单且为本人录入，否则 {@code 3004} / {@code 2010}。</p>
     */
    VitalWithdrawVO withdrawByCompanion(Long orderId, Long recordId);

    /**
     * 陪诊员：本单已录入的体征时间线（只读）。
     *
     * <p>校验顺序：订单不存在 {@code 3001} → 非本单陪诊员 {@code 3004} →
     * 订单未进入服务（{@code IN_SERVICE / COMPLETED / REVIEWED} 之外）{@code 3002}。
     * 与写入链路的差别只在状态边界：写入 / 撤回要求「服务中」，
     * 读取允许「已完成 / 已评价」—— 服务结束后仍要能回看本单记录。</p>
     */
    PageResult<VitalSignVO> listForCompanion(Long orderId, VitalSignQuery query);
}

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
 * （合规红线-1：不做诊断、不开药方）。数值上下限只用于拦截误录，
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
}

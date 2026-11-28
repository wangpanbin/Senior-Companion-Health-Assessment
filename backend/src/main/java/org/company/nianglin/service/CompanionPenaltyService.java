package org.company.nianglin.service;

import org.company.nianglin.vo.CompanionProfileVO;

/**
 * 陪诊员违规次数查询（closure-2026-11 · P1-3）。
 *
 * <p>仲裁的「对陪诊员计违规」开关落成订单行的 {@code arbitrate_flag = 1} 之后，
 * 违规次数就成了一个<b>可查询的事实</b>。本服务只提供读取能力，
 * 写入仍在仲裁链（{@code AdminServiceImpl#arbitrate}）。</p>
 *
 * <p>⚠️ 档案 VO 的填充点原本应落在 {@code CompanionServiceImpl}，
 * 但该类属于 B 批独占文件，本批只把聚合能力与 VO 字段就位，
 * 由文件属主在装配陪诊员档案时调用 {@link #fillInto(CompanionProfileVO)}。</p>
 *
 * @author 银龄伴诊团队
 * @since closure-2026-11
 */
public interface CompanionPenaltyService {

    /**
     * 该陪诊员被判违规的订单数。
     *
     * @param companionId 陪诊员用户 ID
     * @return 违规订单数；无违规时为 0（不是 null）
     */
    int penaltyCount(Long companionId);

    /**
     * 把违规次数填进陪诊员档案 VO。
     *
     * @param profile 陪诊员档案 VO；为 {@code null} 时直接返回
     */
    void fillInto(CompanionProfileVO profile);
}

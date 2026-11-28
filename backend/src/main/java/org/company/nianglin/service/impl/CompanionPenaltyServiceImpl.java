package org.company.nianglin.service.impl;

import lombok.RequiredArgsConstructor;
import org.company.nianglin.mapper.CompanionOrderMapper;
import org.company.nianglin.service.CompanionPenaltyService;
import org.company.nianglin.vo.CompanionProfileVO;
import org.springframework.stereotype.Service;

/**
 * 陪诊员违规次数查询实现（closure-2026-11 · P1-3）。
 *
 * @author 银龄伴诊团队
 * @since closure-2026-11
 */
@Service
@RequiredArgsConstructor
public class CompanionPenaltyServiceImpl implements CompanionPenaltyService {

    private final CompanionOrderMapper orderMapper;

    @Override
    public int penaltyCount(Long companionId) {
        if (companionId == null) {
            return 0;
        }
        long count = orderMapper.countPenaltyOrders(companionId);
        // 计数不可能为负，但 SQL 聚合在极端数据下也不该被原样透出去
        return count <= 0 ? 0 : (int) count;
    }

    @Override
    public void fillInto(CompanionProfileVO profile) {
        if (profile == null) {
            return;
        }
        profile.setPenaltyCount(penaltyCount(profile.getId()));
    }
}

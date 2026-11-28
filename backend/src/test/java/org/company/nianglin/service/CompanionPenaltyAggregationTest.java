package org.company.nianglin.service;

import org.company.nianglin.mapper.CompanionOrderMapper;
import org.company.nianglin.service.impl.CompanionPenaltyServiceImpl;
import org.company.nianglin.support.MybatisLambdaCache;
import org.company.nianglin.vo.CompanionProfileVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * 陪诊员违规次数的聚合口径（closure-2026-11 · P1-3）。
 *
 * <p><b>为什么不存计数列</b>：违规次数是订单行的聚合结果，
 * 单独存一列就会出现「计数列与订单行对不上」的双写不一致 ——
 * 而治理动作（「累计违规 N 次限制接单」）建立在计数列上，
 * 一旦对不上，限制与解禁都会做错人。</p>
 *
 * @author 银龄伴诊团队
 * @since closure-2026-11
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("陪诊员违规次数：从订单行聚合，不维护计数列")
class CompanionPenaltyAggregationTest {

    private static final Long COMPANION_ID = 309L;

    @Mock
    private CompanionOrderMapper orderMapper;

    private CompanionPenaltyService service;

    @BeforeEach
    void setUp() {
        MybatisLambdaCache.warmUp();
        service = new CompanionPenaltyServiceImpl(orderMapper);
    }

    @Test
    @DisplayName("违规次数 · 按 arbitrate_flag = 1 的订单数聚合")
    void penaltyCountShouldAggregateFromOrders() {
        given(orderMapper.countPenaltyOrders(COMPANION_ID)).willReturn(3L);

        assertEquals(3, service.penaltyCount(COMPANION_ID));
    }

    @Test
    @DisplayName("违规次数 · 没有违规订单时返回 0，而不是 null")
    void penaltyCountShouldReturnZeroWhenNoPenalty() {
        given(orderMapper.countPenaltyOrders(COMPANION_ID)).willReturn(0L);

        assertEquals(0, service.penaltyCount(COMPANION_ID));
    }

    @Test
    @DisplayName("陪诊员档案 VO 要能带出 penaltyCount")
    void profileVoShouldCarryPenaltyCount() {
        given(orderMapper.countPenaltyOrders(COMPANION_ID)).willReturn(2L);
        // VO 的 id 是「用户 ID」而不是 companion_profile 主键 —— 聚合按它查
        CompanionProfileVO profile = new CompanionProfileVO().setId(COMPANION_ID);

        service.fillInto(profile);

        assertEquals(2, profile.getPenaltyCount());
        verify(orderMapper).countPenaltyOrders(COMPANION_ID);
    }
}

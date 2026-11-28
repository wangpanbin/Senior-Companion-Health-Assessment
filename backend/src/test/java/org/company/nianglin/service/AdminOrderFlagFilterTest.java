package org.company.nianglin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.company.nianglin.dto.AdminOrderQuery;
import org.company.nianglin.entity.CompanionOrder;
import org.company.nianglin.mapper.AdminReadMapper;
import org.company.nianglin.mapper.CompanionOrderMapper;
import org.company.nianglin.mapper.OrderReadMapper;
import org.company.nianglin.service.impl.AdminServiceImpl;
import org.company.nianglin.service.support.UserNameResolver;
import org.company.nianglin.support.MybatisLambdaCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * 管理端订单列表的仲裁标记筛选（closure-2026-11 · P1-3）。
 *
 * <p>仲裁的两个执法开关落成结构化字段之后，管理员要能<b>把它们查出来</b> ——
 * 否则「订单上记了违规」这件事仍然只能靠管理员记得订单号。</p>
 *
 * @author 银龄伴诊团队
 * @since closure-2026-11
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("管理端订单列表：按 arbitrateFlag 筛选")
class AdminOrderFlagFilterTest {

    @Mock
    private CompanionOrderMapper orderMapper;
    @Mock
    private AdminReadMapper adminReadMapper;
    @Mock
    private OrderReadMapper orderReadMapper;
    @Mock
    private UserNameResolver userNameResolver;

    private AdminServiceImpl adminService;

    @BeforeEach
    void setUp() {
        MybatisLambdaCache.warmUp();
        adminService = new AdminServiceImpl(null, null, null, null, orderMapper, null,
                null, adminReadMapper, orderReadMapper, null, null, null,
                null, null, userNameResolver, null, null, null,
                new com.fasterxml.jackson.databind.ObjectMapper(), null);
        // 空页即可：本类只关心筛选条件进了没有 WHERE
        given(orderMapper.selectPage(any(), any())).willReturn(new Page<>());
    }

    @Test
    @DisplayName("筛选 · arbitrateFlag=true 时 WHERE 必须带上 arbitrate_flag")
    void orderListShouldFilterByArbitrateFlag() {
        AdminOrderQuery query = new AdminOrderQuery();
        query.setArbitrateFlag(true);

        adminService.orderList(query);

        assertTrue(captureWrapper().getSqlSegment().contains("arbitrate_flag"),
                "管理端必须能筛出「已判定陪诊员违规」的订单");
    }

    @Test
    @DisplayName("筛选 · 不传 arbitrateFlag 时不加这个条件（不传 = 全部）")
    void orderListShouldNotFilterWhenFlagAbsent() {
        adminService.orderList(new AdminOrderQuery());

        assertFalse(captureWrapper().getSqlSegment().contains("arbitrate_flag"),
                "不传筛选条件时不能偷偷加条件");
    }

    @SuppressWarnings("unchecked")
    private LambdaQueryWrapper<CompanionOrder> captureWrapper() {
        ArgumentCaptor<LambdaQueryWrapper<CompanionOrder>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(orderMapper).selectPage(any(), captor.capture());
        return captor.getValue();
    }
}

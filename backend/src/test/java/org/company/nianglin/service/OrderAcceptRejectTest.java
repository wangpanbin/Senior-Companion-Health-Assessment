package org.company.nianglin.service;

import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.company.nianglin.common.ResultCode;
import org.company.nianglin.config.FulfillmentProperties;
import org.company.nianglin.config.MatchingProperties;
import org.company.nianglin.constant.AuditStatus;
import org.company.nianglin.constant.OrderStatus;
import org.company.nianglin.constant.RoleConstants;
import org.company.nianglin.dto.OrderRejectDTO;
import org.company.nianglin.entity.CompanionOrder;
import org.company.nianglin.entity.CompanionProfile;
import org.company.nianglin.entity.OrderRejectLog;
import org.company.nianglin.entity.OrderStatusLog;
import org.company.nianglin.exception.BusinessException;
import org.company.nianglin.mapper.CompanionOrderMapper;
import org.company.nianglin.mapper.CompanionProfileMapper;
import org.company.nianglin.mapper.OrderCheckinMapper;
import org.company.nianglin.mapper.OrderRejectLogMapper;
import org.company.nianglin.mapper.OrderStatusLogMapper;
import org.company.nianglin.mapper.SysUserMapper;
import org.company.nianglin.security.LoginUser;
import org.company.nianglin.service.impl.OrderServiceImpl;
import org.company.nianglin.service.support.OrderTransitionService;
import org.company.nianglin.service.support.OrderTransitionServiceImpl;
import org.company.nianglin.support.MybatisLambdaCache;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * P0-1 · 拒单后清空接单人 + 接单指派校验。
 *
 * <p>旧语义下拒单不清 {@code companion_id}，于是：</p>
 * <ul>
 *   <li>「被指派的人拒了单」之后这张单<b>再也改派不了</b>（改派前置是「接单人为空」）；</li>
 *   <li>这张单仍挂在他名下，他被一张自己已拒的单无限期占用；</li>
 *   <li>他拒单后仍会收到这张单的推送。</li>
 * </ul>
 *
 * <p>本类锁定「拒单 → 该单回到无指派状态」，以及配套的
 * 「已被指派给他人的单不可被其他陪诊员抢走」（改派语义与抢单语义的边界）。</p>
 *
 * @author 银龄伴诊团队
 * @since closure-2026-11
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("P0-1 · 拒单后清空接单人 + 接单指派校验")
class OrderAcceptRejectTest {

    private static final Long COMPANION_ID = 301L;
    private static final Long FAMILY_ID = 101L;
    private static final Long ORDER_ID = 2001L;

    @Mock
    private CompanionOrderMapper orderMapper;

    @Mock
    private OrderStatusLogMapper statusLogMapper;

    @Mock
    private OrderRejectLogMapper rejectLogMapper;

    @Mock
    private CompanionProfileMapper companionProfileMapper;

    @Mock
    private SysUserMapper sysUserMapper;

    @Mock
    private MessageService messageService;

    @Mock
    private OrderFeeItemService orderFeeItemService;

    @Mock
    private OrderCheckinMapper orderCheckinMapper;

    private OrderServiceImpl orderService;

    @BeforeEach
    void setUp() {
        MybatisLambdaCache.warmUp();
        loginAsCompanion();

        OrderTransitionService transitionService = new OrderTransitionServiceImpl(
                orderMapper, statusLogMapper, companionProfileMapper, sysUserMapper);
        orderService = new OrderServiceImpl(orderMapper, statusLogMapper, rejectLogMapper,
                null, companionProfileMapper, sysUserMapper, null,
                null, messageService, null, new ObjectMapper(),
                transitionService, orderFeeItemService, null, new FulfillmentProperties(),
                null, new MatchingProperties(), orderCheckinMapper);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("拒单 → 接单人被清空、版本号递增（回到「无指派」状态）")
    void rejectShouldClearCompanionAndBumpVersion() {
        givenCompanionAndOrder();
        given(orderMapper.update(any(), any(LambdaUpdateWrapper.class))).willReturn(1);

        orderService.reject(ORDER_ID, rejectDto());

        ArgumentCaptor<LambdaUpdateWrapper<CompanionOrder>> captor = wrapperCaptor();
        verify(orderMapper).update(any(), captor.capture());
        String sqlSet = captor.getValue().getSqlSet();
        assertTrue(sqlSet.contains("companion_id = NULL"),
                "拒单必须把接单人清空，否则这张单再也改派不了：" + sqlSet);
        assertTrue(sqlSet.contains("version = version + 1"),
                "清空接单人要递增 version，保持与抢单/改派的原子性口径一致：" + sqlSet);

        // WHERE 条件必须是「仍指派给我 且 仍是待接单」——
        // 条件不满足时不得把别人的接单人清掉
        AbstractWrapper<?, ?, ?> wrapper = (AbstractWrapper<?, ?, ?>) captor.getValue();
        wrapper.getSqlSegment();
        String params = String.valueOf(wrapper.getParamNameValuePairs().values());
        assertTrue(params.contains(String.valueOf(COMPANION_ID)),
                "条件更新必须限定「仍指派给我」：" + params);
        assertTrue(params.contains(OrderStatus.PENDING.name()),
                "条件更新必须限定「仍是待接单」：" + params);
    }

    @Test
    @DisplayName("拒单时该单已不再指派给我 → 不影响订单，但仍写拒单日志")
    void rejectShouldNotTouchOrderWhenNoLongerAssigned() {
        givenCompanionAndOrder();
        // 条件更新 0 行：这张单已经指派给别人 / 已被接走
        given(orderMapper.update(any(), any(LambdaUpdateWrapper.class))).willReturn(0);

        orderService.reject(ORDER_ID, rejectDto());

        verify(rejectLogMapper).insert(any(OrderRejectLog.class));
        // 条件不满足时也不该再发第二条写入
        verify(orderMapper, times(1)).update(any(), any(LambdaUpdateWrapper.class));
    }

    @Test
    @DisplayName("拒单不改订单状态（不写状态日志，时间线不被污染）")
    void rejectShouldNotChangeOrderStatus() {
        givenCompanionAndOrder();
        given(orderMapper.update(any(), any(LambdaUpdateWrapper.class))).willReturn(1);

        orderService.reject(ORDER_ID, rejectDto());

        verify(statusLogMapper, never()).insert(any(OrderStatusLog.class));
    }

    @Test
    @DisplayName("接单时该单已被指派给他人 → 3003（改派不被不知情的抢单推翻）")
    void acceptShouldRejectWhenAssignedToOther() {
        given(companionProfileMapper.selectList(any()))
                .willReturn(List.of(companionProfile()));
        CompanionOrder assigned = pendingOrder();
        // 这张单管理员已指派给了另一位陪诊员（999 ≠ 我 301）
        assigned.setCompanionId(999L);
        given(orderMapper.selectById(ORDER_ID)).willReturn(assigned);

        BusinessException ex = assertThrows(BusinessException.class, () -> orderService.accept(ORDER_ID));

        assertEquals(ResultCode.ORDER_ALREADY_TAKEN.getCode(), ex.getCode());
        // 拒绝路径不得产生任何订单写入（乐观锁更新也不该发生）
        verify(orderMapper, never()).updateById(any(CompanionOrder.class));
    }

    private void givenCompanionAndOrder() {
        given(companionProfileMapper.selectList(any()))
                .willReturn(List.of(companionProfile()));
        given(orderMapper.selectById(ORDER_ID)).willReturn(pendingOrder());
        given(rejectLogMapper.selectCount(any())).willReturn(0L);
    }

    private CompanionOrder pendingOrder() {
        CompanionOrder order = new CompanionOrder();
        order.setId(ORDER_ID);
        order.setOrderNo("NL20260915000001");
        order.setFamilyId(FAMILY_ID);
        // 这张单是指派给当前陪诊员的（companion_id 非空且等于我）
        order.setCompanionId(COMPANION_ID);
        order.setStatus(OrderStatus.PENDING.name());
        order.setVersion(0);
        return order;
    }

    private CompanionProfile companionProfile() {
        CompanionProfile profile = new CompanionProfile();
        profile.setId(601L);
        profile.setUserId(COMPANION_ID);
        profile.setRealName("李建军");
        profile.setAuditStatus(AuditStatus.APPROVED.name());
        return profile;
    }

    private OrderRejectDTO rejectDto() {
        OrderRejectDTO dto = new OrderRejectDTO();
        dto.setReason("临时有急事，今天无法前往医院陪同");
        return dto;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ArgumentCaptor<LambdaUpdateWrapper<CompanionOrder>> wrapperCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
    }

    private void loginAsCompanion() {
        LoginUser loginUser = new LoginUser(COMPANION_ID, "companion", RoleConstants.COMPANION, 0, "jti",
                System.currentTimeMillis() + 60000);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, loginUser.authorities()));
    }
}

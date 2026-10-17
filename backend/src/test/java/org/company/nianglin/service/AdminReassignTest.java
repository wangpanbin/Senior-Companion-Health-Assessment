package org.company.nianglin.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.company.nianglin.common.ResultCode;
import org.company.nianglin.constant.AuditStatus;
import org.company.nianglin.constant.FulfillmentEventType;
import org.company.nianglin.constant.FulfillmentRisk;
import org.company.nianglin.constant.MessageType;
import org.company.nianglin.constant.OrderStatus;
import org.company.nianglin.constant.RoleConstants;
import org.company.nianglin.constant.TriggerSource;
import org.company.nianglin.constant.WorkStatus;
import org.company.nianglin.dto.OrderReassignDTO;
import org.company.nianglin.entity.AdminOperLog;
import org.company.nianglin.entity.CompanionOrder;
import org.company.nianglin.entity.CompanionProfile;
import org.company.nianglin.entity.OrderFulfillmentEvent;
import org.company.nianglin.exception.BusinessException;
import org.company.nianglin.mapper.AdminOperLogMapper;
import org.company.nianglin.mapper.CompanionOrderMapper;
import org.company.nianglin.mapper.CompanionProfileMapper;
import org.company.nianglin.mapper.OrderFulfillmentEventMapper;
import org.company.nianglin.mapper.OrderRejectLogMapper;
import org.company.nianglin.security.LoginUser;
import org.company.nianglin.service.impl.AdminServiceImpl;
import org.company.nianglin.service.support.OperLogRecorderImpl;
import org.company.nianglin.service.support.UserNameResolver;
import org.company.nianglin.support.MybatisLambdaCache;
import org.company.nianglin.vo.ReassignResultVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 管理员改派单测（V3.1 · T7）。
 *
 * <p>本组保护四条性质：</p>
 *
 * <ol>
 *   <li><b>只处理未接单订单</b>——非待接单、已有接单人一律 3010。
 *       放宽这一条就会出现「两条路径都能改变订单归属」，
 *       事后没人说得清这笔单是谁在什么情况下换的人；</li>
 *   <li><b>本操作不改订单状态</b>——改完仍是 PENDING，
 *       因此不经过订单状态流转唯一入口（ADR-0010）；</li>
 *   <li><b>四件事同事务</b>——条件更新 / 履约事件 / 操作审计 / 三方通知，
 *       任何一步失败整体回滚。只改了订单却没留事件，管理端「已协调」标记永远点不亮；</li>
 *   <li><b>与抢单竞争不失手</b>——条件更新影响 0 行时既不留事件也不发通知。</li>
 * </ol>
 *
 * @author 银龄伴诊团队
 * @since V3.1
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("管理员改派：前置校验 / 不改状态 / 四件事同事务")
class AdminReassignTest {

    private static final Long ORDER_ID = 1001L;
    private static final Long ADMIN_ID = 1L;
    private static final Long FAMILY_ID = 300L;
    private static final Long TARGET_ID = 302L;
    private static final String REASON = "原陪诊员临时有事无法前往，已协调另一位陪诊员接单";

    @Mock
    private CompanionOrderMapper orderMapper;

    @Mock
    private CompanionProfileMapper companionProfileMapper;

    @Mock
    private OrderRejectLogMapper orderRejectLogMapper;

    @Mock
    private OrderFulfillmentEventMapper fulfillmentEventMapper;

    @Mock
    private AdminOperLogMapper operLogMapper;

    @Mock
    private MessageService messageService;

    @Mock
    private OrderService orderService;

    /** 审计要写操作人姓名，OperLogRecorderImpl 会调它 */
    @Mock
    private UserNameResolver userNameResolver;

    private AdminServiceImpl service;

    @BeforeEach
    void setUp() {
        MybatisLambdaCache.warmUp();
        // 审计走真实实现：本组要验证「日志里记的是前后陪诊员」，mock 掉就测不到
        service = new AdminServiceImpl(null, companionProfileMapper, null, null, orderMapper,
                operLogMapper, new OperLogRecorderImpl(operLogMapper, userNameResolver), null, null,
                orderRejectLogMapper, fulfillmentEventMapper,
                messageService, orderService, null, null, null, null, new ObjectMapper());
        loginAsAdmin();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    /* ================================================================== */
    /* 1 · 正常改派                                                        */
    /* ================================================================== */

    @Test
    @DisplayName("正常改派：条件更新 + 事件 + 审计 + 家属与目标两方通知")
    void reassignShouldSucceedAndWriteEverything() {
        given(orderMapper.selectById(ORDER_ID)).willReturn(pendingOrder());
        given(companionProfileMapper.selectById(TARGET_ID)).willReturn(targetProfile());
        given(orderRejectLogMapper.selectCount(any())).willReturn(0L);
        given(orderMapper.update(any(), any(LambdaUpdateWrapper.class))).willReturn(1);

        ReassignResultVO vo = service.reassign(ORDER_ID, reassignDto());

        assertEquals(ORDER_ID, vo.getOrderId());
        assertNull(vo.getFromCompanionId(), "改派前无人接单，from 应为 null");
        assertEquals(TARGET_ID, vo.getToCompanionId());
        assertEquals(FulfillmentRisk.WARNING.name(), vo.getFulfillmentRisk());

        // 四件事里的三件可观察：事件、审计、通知
        assertEquals(FulfillmentEventType.ORDER_REASSIGNED.name(), captureEvent().getEventType());
        assertNotNull(captureOperLog().getRemark(), "审计必须带上改派理由");
        // 三方通知：家属 + 目标陪诊员（原陪诊员为空时只发两方）
        verify(messageService, times(1)).send(eq(FAMILY_ID), eq(MessageType.ORDER_REASSIGNED), eq(ORDER_ID), any());
        verify(messageService, times(1)).send(eq(TARGET_ID), eq(MessageType.ORDER_REASSIGNED), eq(ORDER_ID), any());
    }

    @Test
    @DisplayName("正常改派：审计的 before / after 记的是陪诊员，不是订单状态")
    void reassignShouldLogCompanionBeforeAfter() {
        given(orderMapper.selectById(ORDER_ID)).willReturn(pendingOrder());
        given(companionProfileMapper.selectById(TARGET_ID)).willReturn(targetProfile());
        given(orderRejectLogMapper.selectCount(any())).willReturn(0L);
        given(orderMapper.update(any(), any(LambdaUpdateWrapper.class))).willReturn(1);

        service.reassign(ORDER_ID, reassignDto());

        AdminOperLog log = captureOperLog();
        assertEquals("未指派", log.getBeforeStatus(), "改派前无人接单");
        assertEquals(String.valueOf(TARGET_ID), log.getAfterStatus());
        assertEquals(ORDER_ID, log.getTargetId());
    }

    /* ================================================================== */
    /* 2 · 前置校验                                                        */
    /* ================================================================== */

    @Test
    @DisplayName("订单不存在 → 3001")
    void reassignShouldRejectMissingOrder() {
        given(orderMapper.selectById(ORDER_ID)).willReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.reassign(ORDER_ID, reassignDto()));

        assertEquals(ResultCode.ORDER_NOT_FOUND.getCode(), ex.getCode());
    }

    @ParameterizedTest(name = "订单状态 [{0}] 不可改派")
    @ValueSource(strings = {"ACCEPTED", "IN_SERVICE", "COMPLETED", "REVIEWED", "CANCELLED"})
    @DisplayName("非待接单一律 3010（换人属纠纷处理，走仲裁通道）")
    void reassignShouldRejectNonPendingOrder(String status) {
        CompanionOrder order = pendingOrder();
        order.setStatus(status);
        given(orderMapper.selectById(ORDER_ID)).willReturn(order);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.reassign(ORDER_ID, reassignDto()));

        assertEquals(ResultCode.ORDER_NOT_REASSIGNABLE.getCode(), ex.getCode());
        verify(orderMapper, never()).update(any(), any(LambdaUpdateWrapper.class));
    }

    @Test
    @DisplayName("已有接单人 → 3010")
    void reassignShouldRejectAssignedOrder() {
        CompanionOrder order = pendingOrder();
        order.setCompanionId(401L);
        given(orderMapper.selectById(ORDER_ID)).willReturn(order);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.reassign(ORDER_ID, reassignDto()));

        assertEquals(ResultCode.ORDER_NOT_REASSIGNABLE.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("目标陪诊员不存在 → 2007")
    void reassignShouldRejectMissingTarget() {
        given(orderMapper.selectById(ORDER_ID)).willReturn(pendingOrder());
        given(companionProfileMapper.selectById(TARGET_ID)).willReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.reassign(ORDER_ID, reassignDto()));

        assertEquals(ResultCode.COMPANION_NOT_FOUND.getCode(), ex.getCode());
    }

    @ParameterizedTest(name = "目标资质 [{0}] → 2003")
    @ValueSource(strings = {"PENDING", "REJECTED"})
    @DisplayName("目标资质未通过 → 2003（指派未过审账号等于绕过审核流程）")
    void reassignShouldRejectUnauditedTarget(String auditStatus) {
        given(orderMapper.selectById(ORDER_ID)).willReturn(pendingOrder());
        CompanionProfile target = targetProfile();
        target.setAuditStatus(auditStatus);
        given(companionProfileMapper.selectById(TARGET_ID)).willReturn(target);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.reassign(ORDER_ID, reassignDto()));

        assertEquals(ResultCode.COMPANION_NOT_AUDITED.getCode(), ex.getCode());
    }

    @ParameterizedTest(name = "目标接单状态 [{0}] → 3010")
    @ValueSource(strings = {"REST", "OFFLINE"})
    @DisplayName("目标处于休息 / 离线 → 3010（指派给不想接单的人等于把这单再挂回无人认领）")
    void reassignShouldRejectBusyTarget(String workStatus) {
        given(orderMapper.selectById(ORDER_ID)).willReturn(pendingOrder());
        CompanionProfile target = targetProfile();
        target.setWorkStatus(workStatus);
        given(companionProfileMapper.selectById(TARGET_ID)).willReturn(target);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.reassign(ORDER_ID, reassignDto()));

        assertEquals(ResultCode.ORDER_NOT_REASSIGNABLE.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("目标曾拒过该单 → 3010（再指派回去只会被再拒一次）")
    void reassignShouldRejectTargetWhoRejected() {
        given(orderMapper.selectById(ORDER_ID)).willReturn(pendingOrder());
        given(companionProfileMapper.selectById(TARGET_ID)).willReturn(targetProfile());
        given(orderRejectLogMapper.selectCount(any())).willReturn(1L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.reassign(ORDER_ID, reassignDto()));

        assertEquals(ResultCode.ORDER_NOT_REASSIGNABLE.getCode(), ex.getCode());
        verify(orderMapper, never()).update(any(), any(LambdaUpdateWrapper.class));
    }

    /* ================================================================== */
    /* 3 · 状态不变 + 并发竞争                                            */
    /* ================================================================== */

    @Test
    @DisplayName("改派不改订单状态：更新语句里没有 status 的 set")
    void reassignShouldNotChangeOrderStatus() {
        given(orderMapper.selectById(ORDER_ID)).willReturn(pendingOrder());
        given(companionProfileMapper.selectById(TARGET_ID)).willReturn(targetProfile());
        given(orderRejectLogMapper.selectCount(any())).willReturn(0L);
        given(orderMapper.update(any(), any(LambdaUpdateWrapper.class))).willReturn(1);

        service.reassign(ORDER_ID, reassignDto());

        String sql = captureUpdateWrapper().getSqlSet();
        assertNotNull(sql);
        org.junit.jupiter.api.Assertions.assertFalse(sql.contains("status"),
                "改派只改 companion_id，改了 status 就等于开了第二条状态变更路径：" + sql);
        // 状态流转唯一入口零调用
        verify(orderService, never()).forceTerminal(any(), any(), any());
    }

    @Test
    @DisplayName("与抢单竞争失败（条件更新 0 行）→ 3010，且不留事件、不发通知、不写审计")
    void reassignShouldFailCleanlyWhenClaimLost() {
        given(orderMapper.selectById(ORDER_ID)).willReturn(pendingOrder());
        given(companionProfileMapper.selectById(TARGET_ID)).willReturn(targetProfile());
        given(orderRejectLogMapper.selectCount(any())).willReturn(0L);
        given(orderMapper.update(any(), any(LambdaUpdateWrapper.class))).willReturn(0);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.reassign(ORDER_ID, reassignDto()));

        assertEquals(ResultCode.ORDER_NOT_REASSIGNABLE.getCode(), ex.getCode());
        verify(fulfillmentEventMapper, never()).insert(any(OrderFulfillmentEvent.class));
        verify(operLogMapper, never()).insert(any(AdminOperLog.class));
        verify(messageService, never()).send(any(), any(MessageType.class), any(), any());
    }

    /* ================================================================== */
    /* 4 · 理由字段                                                        */
    /* ================================================================== */

    @Test
    @DisplayName("理由命中医疗建议词表 → 3007，且不写任何数据")
    void reassignShouldRejectMedicalReason() {
        // 理由合规校验排在查单之前：不合规的理由无论订单是否存在都不该落库，
        // 因此这里刻意不给 orderMapper 打桩，验证它根本没被查到
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.reassign(ORDER_ID,
                        new OrderReassignDTO().setCompanionId(TARGET_ID)
                                .setReason("建议服用两片降压药后再前往医院")));

        assertEquals(ResultCode.CONTENT_SENSITIVE.getCode(), ex.getCode());
        verify(orderMapper, never()).update(any(), any(LambdaUpdateWrapper.class));
    }

    @Test
    @DisplayName("理由过短 → 由 DTO 校验拦下（10 字下限），不进 Service")
    void shortReasonShouldFailValidation() {
        OrderReassignDTO dto = new OrderReassignDTO();
        dto.setCompanionId(TARGET_ID);
        dto.setReason("换人");

        // 这里只验证 DTO 上确实挂了长度约束（@Size min=10），
        // 真正的 400 由 Controller 的 @Valid 触发，见 AdminAccessMatrixTest
        jakarta.validation.Validator validator =
                jakarta.validation.Validation.buildDefaultValidatorFactory().getValidator();
        org.junit.jupiter.api.Assertions.assertFalse(validator.validate(dto).isEmpty(),
                "理由短于 10 字必须被校验注解拦下");
    }

    /* ================================================================== */
    /* 测试夹具                                                            */
    /* ================================================================== */

    private void loginAsAdmin() {
        LoginUser loginUser = new LoginUser(ADMIN_ID, "admin", RoleConstants.ADMIN, 0, "jti",
                System.currentTimeMillis() + 60000);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, loginUser.authorities()));
    }

    private OrderReassignDTO reassignDto() {
        return new OrderReassignDTO().setCompanionId(TARGET_ID).setReason(REASON);
    }

    private CompanionOrder pendingOrder() {
        CompanionOrder order = new CompanionOrder();
        order.setId(ORDER_ID);
        order.setOrderNo("NL20261017000001");
        order.setFamilyId(FAMILY_ID);
        order.setElderId(501L);
        order.setCompanionId(null);
        order.setStatus(OrderStatus.PENDING.name());
        order.setFulfillmentRisk(FulfillmentRisk.WARNING.name());
        order.setCreateTime(LocalDateTime.now().minusMinutes(45));
        return order;
    }

    private CompanionProfile targetProfile() {
        CompanionProfile profile = new CompanionProfile();
        profile.setUserId(TARGET_ID);
        profile.setRealName("李四军");
        profile.setAuditStatus(AuditStatus.APPROVED.name());
        profile.setWorkStatus(WorkStatus.AVAILABLE.name());
        return profile;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private OrderFulfillmentEvent captureEvent() {
        ArgumentCaptor<OrderFulfillmentEvent> captor = ArgumentCaptor.forClass(OrderFulfillmentEvent.class);
        verify(fulfillmentEventMapper, times(1)).insert(captor.capture());
        return captor.getValue();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private AdminOperLog captureOperLog() {
        ArgumentCaptor<AdminOperLog> captor = ArgumentCaptor.forClass(AdminOperLog.class);
        verify(operLogMapper, times(1)).insert(captor.capture());
        return captor.getValue();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private LambdaUpdateWrapper<CompanionOrder> captureUpdateWrapper() {
        ArgumentCaptor<LambdaUpdateWrapper> captor = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(orderMapper, times(1)).update(any(), captor.capture());
        return captor.getValue();
    }
}

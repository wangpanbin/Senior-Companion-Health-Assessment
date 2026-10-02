package org.company.nianglin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.company.nianglin.common.ResultCode;
import org.company.nianglin.constant.OrderStatus;
import org.company.nianglin.constant.RoleConstants;
import org.company.nianglin.dto.ArbitrateDTO;
import org.company.nianglin.entity.AdminOperLog;
import org.company.nianglin.entity.CompanionOrder;
import org.company.nianglin.exception.BusinessException;
import org.company.nianglin.mapper.AdminOperLogMapper;
import org.company.nianglin.mapper.CompanionOrderMapper;
import org.company.nianglin.mapper.CompanionProfileMapper;
import org.company.nianglin.mapper.OrderStatusLogMapper;
import org.company.nianglin.mapper.SysUserMapper;
import org.company.nianglin.security.LoginUser;
import org.company.nianglin.service.impl.AdminServiceImpl;
import org.company.nianglin.service.impl.OrderServiceImpl;
import org.company.nianglin.service.support.OperLogRecorderImpl;
import org.company.nianglin.service.support.OrderTransitionService;
import org.company.nianglin.service.support.OrderTransitionServiceImpl;
import org.company.nianglin.service.support.UserNameResolver;
import org.company.nianglin.support.MybatisLambdaCache;
import org.company.nianglin.vo.ArbitrateResultVO;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 管理员纠纷处理单测（M9 · §9 arbitrate + §12 forceTerminal）。
 *
 * <p><b>本类锁定的是一个已被修复、且在种子数据里完全看不出来的缺陷。</b>
 * arbitrate 与 forceTerminal 同处一个 {@code @Transactional}，共享同一个 SqlSession；
 * MyBatis 一级缓存（{@code localCacheScope=SESSION}，默认开）��「相同语句 + 相同参数」
 * 返回<b>同一个对象实例</b>。于是 forceTerminal 内部 {@code selectById} 拿到的就是
 * arbitrate 里那个 {@code before} 实例，{@code orderTransitionService.forceTerminal}
 * 随即把它的 {@code status} 改成目标状态。若 arbitrate 在写日志时才读
 * {@code before.getStatus()}，拿到的已是新值，日志就成了「CANCELLED → CANCELLED」——
 * 纠纷取证最关键的一环（管理员到底覆盖了哪个状态）就此丢失。</p>
 *
 * <p>为什么修完还是可能复发：种子数据里那两条 {@code ARBITRATE_ORDER} 记录是
 * SQL 直接 INSERT 的，没走这条代码路径，<b>缺陷在种子数据里是看不出来的</b>；
 * 删除 {@code before_status} 断言后全绿依旧。因此本类用真实
 * {@link OrderServiceImpl}（不 mock）来跑通「读取 → 改写 → 写日志」全链路，
 * 并让 mock mapper 返回<b>同一实例</b>来忠实模拟一级缓存 —— 正是这条断言
 * 把回归挡在门外。</p>
 *
 * <p>其余用例盯的是 forceTerminal 自身的边界：只允许 COMPLETED / CANCELLED、
 * 订单不存在、取消要留取消原因、双方都要收到通知。</p>
 *
 * @author 银龄伴诊团队
 * @since M9
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("纠纷处理：before_status 不得被一级缓存污染 / 强制终态边界")
class AdminArbitrateTest {

    private static final Long ADMIN_ID = 1L;
    private static final Long ORDER_ID = 2001L;
    private static final Long FAMILY_ID = 101L;
    private static final Long COMPANION_ID = 301L;

    @Mock
    private CompanionOrderMapper orderMapper;

    @Mock
    private OrderStatusLogMapper statusLogMapper;

    @Mock
    private CompanionProfileMapper companionProfileMapper;

    @Mock
    private SysUserMapper sysUserMapper;

    @Mock
    private AdminOperLogMapper operLogMapper;

    @Mock
    private MessageService messageService;

    @Mock
    private UserNameResolver userNameResolver;

    /** 单实例缓存：让 selectById 每次都返回<b>同一个</b> order 对象 */
    private CompanionOrder cached;

    private AdminServiceImpl adminService;
    private OrderServiceImpl orderService;

    @BeforeEach
    void setUp() {
        MybatisLambdaCache.warmUp();
        loginAsAdmin();

        cached = order(OrderStatus.IN_SERVICE);

        // 状态流转走**真实实现**（与 OrderServiceTest 一致）：本类要验证的正是
        // forceTerminal 对 order 实例的改写能否被 arbitrate 看见，mock 掉就测不到了
        OrderTransitionService transitionService = new OrderTransitionServiceImpl(
                orderMapper, statusLogMapper, companionProfileMapper, sysUserMapper);
        orderService = new OrderServiceImpl(orderMapper, statusLogMapper, null,
                null, companionProfileMapper, sysUserMapper, null,
                null, messageService, null, new ObjectMapper(),
                transitionService, null);

        adminService = new AdminServiceImpl(null, companionProfileMapper, sysUserMapper,
                null, orderMapper, operLogMapper,
                new OperLogRecorderImpl(operLogMapper, userNameResolver), null, null,
                messageService, orderService, userNameResolver, null, null, null,
                new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    /* ================================================================== */
    /* 核心回归：before_status 必须是被覆盖前的状态                            */
    /* ================================================================== */

    @Test
    @DisplayName("纠纷处理 · IN_SERVICE 强制改 CANCELLED：before_status 必须是 IN_SERVICE 而非 CANCELLED")
    void arbitrateShouldRecordPreChangeStatusInLog() {
        givenCachedOrder();

        ArbitrateResultVO result = adminService.arbitrate(ORDER_ID, arbitrateDto("CANCELLED"));

        assertNotNull(result);
        assertEquals("CANCELLED", result.getStatus());

        // 唯一的防线：日志里必须留下「管理员覆盖了哪个状态」。
        // 若把快照挪回写日志处再读 before.getStatus()，这里会拿到 CANCELLED 而失败
        AdminOperLog operLog = captureOperLog();
        assertEquals("IN_SERVICE", operLog.getBeforeStatus(),
                "before_status 被一级缓存污染成变更后的状态，纠纷取证线索丢失");
        assertEquals("CANCELLED", operLog.getAfterStatus());
    }

    @Test
    @DisplayName("纠纷处理 · PENDING 强制改 COMPLETED：before_status 必须是 PENDING")
    void arbitrateShouldRecordPreChangeStatusWhenCompletingPendingOrder() {
        cached = order(OrderStatus.PENDING);
        givenCachedOrder();

        adminService.arbitrate(ORDER_ID, arbitrateDto("COMPLETED"));

        AdminOperLog operLog = captureOperLog();
        assertEquals("PENDING", operLog.getBeforeStatus());
        assertEquals("COMPLETED", operLog.getAfterStatus());
    }

    @Test
    @DisplayName("纠纷处理 · 前后状态不得相同（同状态会掩盖「是否真的改过」）")
    void arbitrateShouldNeverLogIdenticalBeforeAndAfter() {
        givenCachedOrder();

        adminService.arbitrate(ORDER_ID, arbitrateDto("CANCELLED"));

        AdminOperLog operLog = captureOperLog();
        assertTrue(!operLog.getBeforeStatus().equals(operLog.getAfterStatus()),
                "before_status 与 after_status 相同，说明记录的是同一个状态，取证价值为零");
    }

    @Test
    @DisplayName("纠纷处理 · 单号同样取快照（forceTerminal 不会改单号，但同源同因，一并锁住）")
    void arbitrateShouldSnapshotOrderNoBeforeForce() {
        givenCachedOrder();

        adminService.arbitrate(ORDER_ID, arbitrateDto("CANCELLED"));

        assertEquals("NO-2001", captureOperLog().getTargetDesc());
    }

    @Test
    @DisplayName("纠纷处理 · 强制取消要写 cancelTime / cancelReason（事后追责的依据）")
    void arbitrateToCancelledShouldPersistCancelReason() {
        givenCachedOrder();

        adminService.arbitrate(ORDER_ID, arbitrateDto("CANCELLED"));

        assertNotNull(cached.getCancelTime());
        assertTrue(cached.getCancelReason().contains("管理员纠纷处理"),
                "取消原因缺失则事后无法追责：" + cached.getCancelReason());
    }

    @Test
    @DisplayName("纠纷处理 · 家属与陪诊员都要收到通知（只通知一方，另一方会被蒙在鼓里）")
    void arbitrateShouldNotifyBothParties() {
        givenCachedOrder();

        adminService.arbitrate(ORDER_ID, arbitrateDto("CANCELLED"));

        verify(messageService).send(org.mockito.ArgumentMatchers.eq(FAMILY_ID), any(), any(), any());
        verify(messageService).send(org.mockito.ArgumentMatchers.eq(COMPANION_ID), any(), any(), any());
    }

    @Test
    @DisplayName("纠纷处理 · 退费 / 违规标记要写进 remark（一期无在线支付，只能靠这条记账线索）")
    void arbitrateShouldAppendRefundAndPenaltyToRemark() {
        givenCachedOrder();
        ArbitrateDTO dto = arbitrateDto("CANCELLED");
        dto.setRefundToFamily(true);
        dto.setPenaltyToCompanion(true);

        adminService.arbitrate(ORDER_ID, dto);

        String remark = captureOperLog().getRemark();
        assertTrue(remark.contains("标记退费给家属"), remark);
        assertTrue(remark.contains("对陪诊员计违规"), remark);
    }

    /* ================================================================== */
    /* forceTerminal 自身的边界                                             */
    /* ================================================================== */

    @Test
    @DisplayName("强制终态 · 目标不是 COMPLETED / CANCELLED → PARAM_ERROR（否则状态机会被拉回中间态）")
    void forceTerminalShouldRejectNonForceableTarget() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> adminService.arbitrate(ORDER_ID, arbitrateDto("IN_SERVICE")));

        assertEquals(ResultCode.PARAM_ERROR.getCode(), ex.getCode());
        verify(operLogMapper, never()).insert(any(AdminOperLog.class));
        verify(messageService, never()).send(any(), any(), any(), any());
    }

    @Test
    @DisplayName("强制终态 · 目标状态拼错 → PARAM_ERROR（拼错的枚举不能被静默当成合法）")
    void forceTerminalShouldRejectUnknownTargetStatus() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> adminService.arbitrate(ORDER_ID, arbitrateDto("CANCELD")));

        assertEquals(ResultCode.PARAM_ERROR.getCode(), ex.getCode());
        verify(operLogMapper, never()).insert(any(AdminOperLog.class));
    }

    @Test
    @DisplayName("强制终态 · 订单不存在 → 3003，且不留痕不通知")
    void arbitrateShouldRejectMissingOrder() {
        given(orderMapper.selectById(ORDER_ID)).willReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> adminService.arbitrate(ORDER_ID, arbitrateDto("CANCELLED")));

        assertEquals(ResultCode.ORDER_NOT_FOUND.getCode(), ex.getCode());
        verify(operLogMapper, never()).insert(any(AdminOperLog.class));
        verify(messageService, never()).send(any(), any(), any(), any());
    }

    /* ================================================================== */

    /**
     * 让 mock mapper 每次 selectById 都返回<b>同一个</b>实例 —— 模拟 MyBatis 一级缓存
     * （{@code localCacheScope=SESSION}）对「相同语句 + 相同参数」返回同一对象的行为。
     * 这正是缺陷的触发条件：forceTerminal 改写该实例后，arbitrate 持有的引用同步被改。
     *
     * <p>{@code updateById} 必须回 1：{@code forceTerminal} 用影响行数判乐观锁
     * （{@code rows == 0} → 409 冲突），mock 默认返回 0 会让用例停在并发保护上，
     * 根本走不到写日志那一步。</p>
     */
    private void givenCachedOrder() {
        given(orderMapper.selectById(ORDER_ID)).willReturn(cached);
        given(orderMapper.updateById(any(CompanionOrder.class))).willReturn(1);
    }

    private AdminOperLog captureOperLog() {
        ArgumentCaptor<AdminOperLog> captor = ArgumentCaptor.forClass(AdminOperLog.class);
        verify(operLogMapper).insert(captor.capture());
        return captor.getValue();
    }

    private CompanionOrder order(OrderStatus status) {
        CompanionOrder order = new CompanionOrder();
        order.setId(ORDER_ID);
        order.setOrderNo("NO-2001");
        order.setFamilyId(FAMILY_ID);
        order.setCompanionId(COMPANION_ID);
        order.setStatus(status.name());
        return order;
    }

    private ArbitrateDTO arbitrateDto(String targetStatus) {
        ArbitrateDTO dto = new ArbitrateDTO();
        dto.setTargetStatus(targetStatus);
        // 注意 @Size(min = 10)：Service 单测不走 @Valid，但文案长度对齐真实接口约束
        dto.setResult("陪诊员未按时到院，核实后判定违约");
        return dto;
    }

    private void loginAsAdmin() {
        LoginUser loginUser = new LoginUser(ADMIN_ID, "admin", RoleConstants.ADMIN, 0, "jti",
                System.currentTimeMillis() + 60000);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, loginUser.authorities()));
    }
}

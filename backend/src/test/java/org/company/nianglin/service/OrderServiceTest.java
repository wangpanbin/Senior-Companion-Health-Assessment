package org.company.nianglin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.company.nianglin.common.PageResult;
import org.company.nianglin.common.ResultCode;
import org.company.nianglin.constant.AuditStatus;
import org.company.nianglin.constant.OrderStatus;
import org.company.nianglin.constant.PaymentStatus;
import org.company.nianglin.constant.RoleConstants;
import org.company.nianglin.dto.OrderCancelDTO;
import org.company.nianglin.dto.OrderCompleteDTO;
import org.company.nianglin.dto.OrderCreateDTO;
import org.company.nianglin.dto.OrderHallQuery;
import org.company.nianglin.dto.OrderQuery;
import org.company.nianglin.dto.OrderRejectDTO;
import org.company.nianglin.entity.CompanionOrder;
import org.company.nianglin.entity.CompanionProfile;
import org.company.nianglin.entity.ElderProfile;
import org.company.nianglin.entity.OrderRejectLog;
import org.company.nianglin.entity.OrderStatusLog;
import org.company.nianglin.exception.BusinessException;
import org.company.nianglin.mapper.CompanionOrderMapper;
import org.company.nianglin.mapper.CompanionProfileMapper;
import org.company.nianglin.mapper.ElderProfileMapper;
import org.company.nianglin.mapper.OrderReadMapper;
import org.company.nianglin.mapper.OrderRejectLogMapper;
import org.company.nianglin.mapper.OrderStatusLogMapper;
import org.company.nianglin.mapper.SysUserMapper;
import org.company.nianglin.security.LoginUser;
import org.company.nianglin.service.MessageService;
import org.company.nianglin.service.impl.OrderServiceImpl;
import org.company.nianglin.support.MybatisLambdaCache;
import org.company.nianglin.vo.OrderAcceptResultVO;
import org.company.nianglin.vo.OrderCreateResultVO;
import org.company.nianglin.vo.OrderFlowResultVO;
import org.company.nianglin.vo.OrderTimelineVO;
import org.company.nianglin.vo.OrderVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 陪诊订单服务单测。
 *
 * <p>重点不在「流程能不能跑通」（那是端到端脚本的活），而在<b>三条没人会主动去点的分支</b>：</p>
 * <ol>
 *   <li><b>状态机边界</b>：跳级、回退、终态再流转，以及"先判状态还是先判身份"的顺序；</li>
 *   <li><b>失败时的副作用</b>：越权、状态不合法被拒之后，有没有偷偷改掉数据库；</li>
 *   <li><b>并发分支</b>：乐观锁返回 0 行时是不是真的转成了 3003 而不是当成成功。</li>
 * </ol>
 *
 * <p>第 3 条尤其重要 —— 它是 M4 的核心验收项，而这行代码在正常流程里永远不会被执行，
 * 单测是唯一能在不装 JMeter 的情况下把它逼出来的手段。</p>
 *
 * @author 银龄伴诊团队
 * @since M4
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("陪诊订单服务：状态机 / 归属校验 / 乐观锁 / 合规")
class OrderServiceTest {

    private static final Long FAMILY_ID = 101L;
    private static final Long OTHER_FAMILY_ID = 102L;
    private static final Long ELDER_ID = 401L;
    private static final Long ELDER_USER_ID = 201L;
    private static final Long COMPANION_ID = 301L;
    private static final Long OTHER_COMPANION_ID = 302L;
    private static final Long ORDER_ID = 2001L;

    @Mock
    private CompanionOrderMapper orderMapper;

    @Mock
    private OrderStatusLogMapper statusLogMapper;

    @Mock
    private OrderRejectLogMapper rejectLogMapper;

    @Mock
    private ElderProfileMapper elderProfileMapper;

    @Mock
    private CompanionProfileMapper companionProfileMapper;

    @Mock
    private SysUserMapper sysUserMapper;

    @Mock
    private OrderReadMapper orderReadMapper;

    @Mock
    private ElderService elderService;

    @Mock
    private MessageService messageService;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private OrderServiceImpl orderService;

    @BeforeEach
    void setUp() {
        // 纯 Mockito 单测不起 Spring 容器，MyBatis-Plus 的 lambda 列名缓存是空的，
        // wrapper.set(...) 会直接抛 "can not find lambda cache"。详见 MybatisLambdaCache。
        MybatisLambdaCache.warmUp();

        orderService = new OrderServiceImpl(orderMapper, statusLogMapper, rejectLogMapper,
                elderProfileMapper, companionProfileMapper, sysUserMapper, orderReadMapper,
                elderService, messageService, redisTemplate, new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    /* ================================================================== */
    /* 1. 创建订单                                                         */
    /* ================================================================== */

    @Test
    @DisplayName("下单：就诊时间早于当前时间返回 3005，不产生任何写入")
    void createShouldRejectPastVisitTime() {
        loginAs(RoleConstants.FAMILY, FAMILY_ID);
        given(elderService.requireAccessible(ELDER_ID)).willReturn(elderProfile());

        OrderCreateDTO dto = createDto(LocalDateTime.now().minusHours(1), null);

        BusinessException ex = assertThrows(BusinessException.class, () -> orderService.create(dto));

        assertEquals(ResultCode.ORDER_TIME_INVALID.getCode(), ex.getCode());
        verify(orderMapper, never()).insert(any(CompanionOrder.class));
    }

    @Test
    @DisplayName("下单：老人不在自己名下时把 M3 的 2006 原样抛出")
    void createShouldPropagateOwnershipError() {
        loginAs(RoleConstants.FAMILY, FAMILY_ID);
        given(elderService.requireAccessible(ELDER_ID))
                .willThrow(new BusinessException(ResultCode.NO_PERMISSION_FOR_ELDER));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.create(createDto(nextWeekdayAt(10), null)));

        assertEquals(ResultCode.NO_PERMISSION_FOR_ELDER.getCode(), ex.getCode());
        verify(orderMapper, never()).insert(any(CompanionOrder.class));
    }

    @Test
    @DisplayName("下单：工作日白天按基础价 128.00，不传 fee 时由后端算")
    void createShouldUseBaseFeeOnWeekdayDaytime() {
        loginAs(RoleConstants.FAMILY, FAMILY_ID);
        stubCreateHappyPath();

        orderService.create(createDto(nextWeekdayAt(10), null));

        CompanionOrder saved = captureInsertedOrder();
        assertEquals(0, new BigDecimal("128.00").compareTo(saved.getFee()));
    }

    @Test
    @DisplayName("下单：夜间（18 点后）加价 30 元")
    void createShouldAddExtraFeeAtNight() {
        loginAs(RoleConstants.FAMILY, FAMILY_ID);
        stubCreateHappyPath();

        orderService.create(createDto(nextWeekdayAt(20), null));

        CompanionOrder saved = captureInsertedOrder();
        assertEquals(0, new BigDecimal("158.00").compareTo(saved.getFee()));
    }

    @Test
    @DisplayName("下单：周末白天同样加价 30 元")
    void createShouldAddExtraFeeOnWeekend() {
        loginAs(RoleConstants.FAMILY, FAMILY_ID);
        stubCreateHappyPath();

        orderService.create(createDto(nextWeekendAt(10), null));

        CompanionOrder saved = captureInsertedOrder();
        assertEquals(0, new BigDecimal("158.00").compareTo(saved.getFee()));
    }

    @Test
    @DisplayName("下单：传了 fee 就用传的（价格规则不在前端）")
    void createShouldHonourGivenFee() {
        loginAs(RoleConstants.FAMILY, FAMILY_ID);
        stubCreateHappyPath();

        orderService.create(createDto(nextWeekdayAt(10), new BigDecimal("199.9")));

        CompanionOrder saved = captureInsertedOrder();
        assertEquals(0, new BigDecimal("199.90").compareTo(saved.getFee()));
    }

    @Test
    @DisplayName("下单：初始状态 PENDING、version=0、UNPAID，并写一条首节点日志")
    void createShouldInitialiseOrder() {
        loginAs(RoleConstants.FAMILY, FAMILY_ID);
        stubCreateHappyPath();

        OrderCreateResultVO result = orderService.create(createDto(nextWeekdayAt(10), null));

        CompanionOrder saved = captureInsertedOrder();
        assertEquals(OrderStatus.PENDING.name(), saved.getStatus());
        // version 必须显式给 0：让它为 null，@Version 生成的 WHERE version = ? 永远匹配不上
        assertEquals(0, saved.getVersion());
        assertEquals(PaymentStatus.UNPAID.name(), saved.getPaymentStatus());
        assertEquals(FAMILY_ID, saved.getFamilyId());
        assertEquals(ELDER_ID, saved.getElderId());
        assertNotNull(saved.getOrderNo());
        assertTrue(saved.getOrderNo().matches("^NL\\d{8}\\d{6}$"),
                "订单号格式应为 NL + yyyyMMdd + 6 位序列，实际：" + saved.getOrderNo());
        assertEquals(OrderStatus.PENDING.name(), result.getStatus());
        assertEquals("待接单", result.getStatusLabel());

        ArgumentCaptor<OrderStatusLog> logCaptor = ArgumentCaptor.forClass(OrderStatusLog.class);
        verify(statusLogMapper).insert(logCaptor.capture());
        OrderStatusLog first = logCaptor.getValue();
        assertNull(first.getFromStatus(), "首个节点的 fromStatus 必须是 null");
        assertEquals(OrderStatus.PENDING.name(), first.getToStatus());
        assertEquals(RoleConstants.FAMILY, first.getOperatorRole());
    }

    @Test
    @DisplayName("下单：Redis 拿不到序列号时明确报错，而不是拼出一个重复订单号")
    void createShouldFailFastWhenRedisUnavailable() {
        loginAs(RoleConstants.FAMILY, FAMILY_ID);
        given(elderService.requireAccessible(ELDER_ID)).willReturn(elderProfile());
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.increment(anyString())).willReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.create(createDto(nextWeekdayAt(10), null)));

        assertEquals(ResultCode.SYSTEM_ERROR.getCode(), ex.getCode());
        verify(orderMapper, never()).insert(any(CompanionOrder.class));
    }

    /* ================================================================== */
    /* 2. 我的订单列表（数据范围由角色决定）                                 */
    /* ================================================================== */

    @Test
    @DisplayName("列表：家属范围锁定在 family_id，且列表项姓名脱敏、不返回地址")
    void myOrdersShouldScopeToFamily() {
        loginAs(RoleConstants.FAMILY, FAMILY_ID);
        given(orderMapper.selectPage(any(), any())).willReturn(pageOf(pendingOrder()));
        given(orderReadMapper.selectEldersIgnoringLogicDelete(any())).willReturn(List.of(elderProfile()));

        PageResult<OrderVO> result = orderService.myOrders(new OrderQuery());

        LambdaQueryWrapper<CompanionOrder> wrapper = captureQueryWrapper();
        assertTrue(wrapper.getSqlSegment().contains("family_id"),
                "家属列表必须按 family_id 收窄：" + wrapper.getSqlSegment());
        assertTrue(wrapper.getParamNameValuePairs().containsValue(FAMILY_ID),
                "参数里应带上当前家属 ID");
        assertFalse(wrapper.getParamNameValuePairs().containsValue(COMPANION_ID),
                "家属列表不应该出现陪诊员 ID 作为筛选值");

        OrderVO vo = result.getRecords().get(0);
        assertEquals("张*海", vo.getElderName());
        assertNull(vo.getAddress(), "我的订单列表不返回地址");
        assertEquals("128.00", vo.getFee());
    }

    @Test
    @DisplayName("列表：陪诊员范围锁定在 companion_id")
    void myOrdersShouldScopeToCompanion() {
        loginAs(RoleConstants.COMPANION, COMPANION_ID);
        CompanionOrder order = pendingOrder();
        order.setCompanionId(COMPANION_ID);
        order.setStatus(OrderStatus.ACCEPTED.name());
        given(orderMapper.selectPage(any(), any())).willReturn(pageOf(order));
        given(orderReadMapper.selectEldersIgnoringLogicDelete(any())).willReturn(List.of(elderProfile()));
        given(companionProfileMapper.selectList(any())).willReturn(List.of(companionProfile(AuditStatus.APPROVED)));

        orderService.myOrders(new OrderQuery());

        LambdaQueryWrapper<CompanionOrder> wrapper = captureQueryWrapper();
        assertTrue(wrapper.getSqlSegment().contains("companion_id"),
                "陪诊员列表必须按 companion_id 收窄：" + wrapper.getSqlSegment());
    }

    @Test
    @DisplayName("列表：老人账号没有任何档案时直接返回空页，不查订单表（避免 IN () 语法错）")
    void myOrdersShouldReturnEmptyForElderWithoutProfile() {
        loginAs(RoleConstants.ELDER, ELDER_USER_ID);
        given(elderProfileMapper.selectList(any())).willReturn(List.of());

        PageResult<OrderVO> result = orderService.myOrders(new OrderQuery());

        assertEquals(0L, result.getTotal());
        assertNotNull(result.getRecords());
        verify(orderMapper, never()).selectPage(any(), any());
    }

    @Test
    @DisplayName("列表：老人只看自己作为就诊人的订单")
    void myOrdersShouldScopeToElderProfileOfSelf() {
        loginAs(RoleConstants.ELDER, ELDER_USER_ID);
        given(elderProfileMapper.selectList(any())).willReturn(List.of(elderProfile()));
        given(orderMapper.selectPage(any(), any())).willReturn(pageOf(pendingOrder()));
        given(orderReadMapper.selectEldersIgnoringLogicDelete(any())).willReturn(List.of(elderProfile()));

        orderService.myOrders(new OrderQuery());

        LambdaQueryWrapper<CompanionOrder> wrapper = captureQueryWrapper();
        assertTrue(wrapper.getSqlSegment().contains("elder_id"), wrapper.getSqlSegment());
    }

    @Test
    @DisplayName("列表：状态传错单词直接 400，不静默返回全量（否则前端以为筛选生效了）")
    void myOrdersShouldRejectIllegalStatus() {
        loginAs(RoleConstants.FAMILY, FAMILY_ID);

        OrderQuery query = new OrderQuery();
        query.setStatus("PENDNG");

        BusinessException ex = assertThrows(BusinessException.class, () -> orderService.myOrders(query));

        assertEquals(ResultCode.PARAM_ERROR.getCode(), ex.getCode());
        verify(orderMapper, never()).selectPage(any(), any());
    }

    @Test
    @DisplayName("列表：多值状态逗号分隔可正常下推")
    void myOrdersShouldSupportMultiStatus() {
        loginAs(RoleConstants.FAMILY, FAMILY_ID);
        given(orderMapper.selectPage(any(), any())).willReturn(pageOf(pendingOrder()));

        OrderQuery query = new OrderQuery();
        query.setStatus("pending, ACCEPTED ,PENDING");

        orderService.myOrders(query);

        // 必须先触发一次 SQL 渲染再读参数表：
        // eq(...) 走的是 maybeDo（立即求值），参数当场就登记进去了；
        // 而 in(column, Collection) 把 formatParam 写在 lambda 体内（惰性），
        // 不渲染就一个参数都读不到 —— 这与生产行为无关，纯粹是测试写法上的坑
        LambdaQueryWrapper<CompanionOrder> wrapper = captureQueryWrapper();
        wrapper.getSqlSegment();
        Map<String, Object> params = wrapper.getParamNameValuePairs();
        assertTrue(params.values().stream().anyMatch(v -> String.valueOf(v).contains(OrderStatus.PENDING.name())));
        assertTrue(params.values().stream().anyMatch(v -> String.valueOf(v).contains(OrderStatus.ACCEPTED.name())));
    }

    @Test
    @DisplayName("列表：开始日期晚于结束日期返回 400")
    void myOrdersShouldRejectReversedDateRange() {
        loginAs(RoleConstants.FAMILY, FAMILY_ID);

        OrderQuery query = new OrderQuery();
        query.setStartDate("2026-09-30");
        query.setEndDate("2026-09-01");

        BusinessException ex = assertThrows(BusinessException.class, () -> orderService.myOrders(query));

        assertEquals(ResultCode.PARAM_ERROR.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("列表：日期格式非法返回 400 并指明是哪个参数")
    void myOrdersShouldRejectBadDateFormat() {
        loginAs(RoleConstants.FAMILY, FAMILY_ID);

        OrderQuery query = new OrderQuery();
        query.setStartDate("2026/09/01");

        BusinessException ex = assertThrows(BusinessException.class, () -> orderService.myOrders(query));

        assertEquals(ResultCode.PARAM_ERROR.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("startDate"), ex.getMessage());
    }

    /* ================================================================== */
    /* 3. 大厅（双重拦截 + 排除已拒）                                        */
    /* ================================================================== */

    @Test
    @DisplayName("大厅：没有陪诊员快照（从未申请）返回 2003")
    void hallShouldRejectCompanionWithoutProfile() {
        loginAs(RoleConstants.COMPANION, COMPANION_ID);
        given(companionProfileMapper.selectList(any())).willReturn(List.of());

        BusinessException ex = assertThrows(BusinessException.class, () -> orderService.hall(new OrderHallQuery()));

        assertEquals(ResultCode.COMPANION_NOT_AUDITED.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("大厅：资质还在待审核同样返回 2003 —— 角色对不代表能接单")
    void hallShouldRejectPendingAudit() {
        loginAs(RoleConstants.COMPANION, COMPANION_ID);
        given(companionProfileMapper.selectList(any())).willReturn(List.of(companionProfile(AuditStatus.PENDING)));

        BusinessException ex = assertThrows(BusinessException.class, () -> orderService.hall(new OrderHallQuery()));

        assertEquals(ResultCode.COMPANION_NOT_AUDITED.getCode(), ex.getCode());
        verify(orderMapper, never()).selectPage(any(), any());
    }

    @Test
    @DisplayName("大厅：只查 PENDING 且未过期，排除自己拒过的单，并返回地址")
    void hallShouldReturnPendingAndExcludeRejected() {
        loginAs(RoleConstants.COMPANION, COMPANION_ID);
        given(companionProfileMapper.selectList(any()))
                .willReturn(List.of(companionProfile(AuditStatus.APPROVED)));
        given(rejectLogMapper.selectList(any())).willReturn(List.of(rejectLog(9999L)));
        given(orderMapper.selectPage(any(), any())).willReturn(pageOf(pendingOrder()));
        given(orderReadMapper.selectEldersIgnoringLogicDelete(any())).willReturn(List.of(elderProfile()));

        PageResult<OrderVO> result = orderService.hall(new OrderHallQuery());

        LambdaQueryWrapper<CompanionOrder> wrapper = captureQueryWrapper();
        String sql = wrapper.getSqlSegment();
        assertTrue(sql.contains("status"), sql);
        assertTrue(sql.contains("visit_time"), "大厅必须按就诊时间过滤掉已过期的单：" + sql);
        assertTrue(sql.contains("NOT IN") || sql.contains("not in"), "已拒订单必须被排除：" + sql);
        assertTrue(wrapper.getParamNameValuePairs().values().stream()
                        .anyMatch(v -> String.valueOf(v).contains("9999")),
                "被排除的订单 ID 应作为参数：" + wrapper.getParamNameValuePairs());

        assertEquals("海南省海口市秀英区秀华路19号 门诊大楼3楼", result.getRecords().get(0).getAddress(),
                "大厅必须返回地址，否则陪诊员无法判断去哪儿");
        assertEquals("张*海", result.getRecords().get(0).getElderName());
    }

    @Test
    @DisplayName("大厅：一条都没拒过时不能拼出 NOT IN ()")
    void hallShouldSkipNotInWhenNoRejection() {
        loginAs(RoleConstants.COMPANION, COMPANION_ID);
        given(companionProfileMapper.selectList(any()))
                .willReturn(List.of(companionProfile(AuditStatus.APPROVED)));
        given(rejectLogMapper.selectList(any())).willReturn(List.of());
        given(orderMapper.selectPage(any(), any())).willReturn(pageOf());

        orderService.hall(new OrderHallQuery());

        String sql = captureQueryWrapper().getSqlSegment();
        assertFalse(sql.toUpperCase().contains("NOT IN"), "空集合不能生成 NOT IN：" + sql);
    }

    /* ================================================================== */
    /* 4. 接单（乐观锁 —— M4 核心验收点）                                    */
    /* ================================================================== */

    @Test
    @DisplayName("接单：不是待接单状态返回 3002，且不发起更新")
    void acceptShouldRejectNonPending() {
        loginAs(RoleConstants.COMPANION, COMPANION_ID);
        given(companionProfileMapper.selectList(any()))
                .willReturn(List.of(companionProfile(AuditStatus.APPROVED)));
        CompanionOrder order = pendingOrder();
        order.setStatus(OrderStatus.ACCEPTED.name());
        given(orderMapper.selectById(ORDER_ID)).willReturn(order);

        BusinessException ex = assertThrows(BusinessException.class, () -> orderService.accept(ORDER_ID));

        assertEquals(ResultCode.ORDER_STATUS_ILLEGAL.getCode(), ex.getCode());
        verify(orderMapper, never()).updateById(any(CompanionOrder.class));
    }

    @Test
    @DisplayName("接单：乐观锁未命中（affectedRows=0）必须转成 3003，绝不能当成功")
    void acceptShouldReturn3003WhenOptimisticLockFails() {
        loginAs(RoleConstants.COMPANION, COMPANION_ID);
        given(companionProfileMapper.selectList(any()))
                .willReturn(List.of(companionProfile(AuditStatus.APPROVED)));
        given(orderMapper.selectById(ORDER_ID)).willReturn(pendingOrder());
        // 模拟"被别人抢先"：读到的版本已经不是最新的，UPDATE 命中 0 行
        given(orderMapper.updateById(any(CompanionOrder.class))).willReturn(0);

        BusinessException ex = assertThrows(BusinessException.class, () -> orderService.accept(ORDER_ID));

        assertEquals(ResultCode.ORDER_ALREADY_TAKEN.getCode(), ex.getCode());
        // 抢单失败更不能写状态日志，否则时间线上会出现一条并不存在的"已接单"
        verify(statusLogMapper, never()).insert(any(OrderStatusLog.class));
    }

    @Test
    @DisplayName("接单：成功时写入陪诊员、接单时间并记一条日志")
    void acceptShouldSucceed() {
        loginAs(RoleConstants.COMPANION, COMPANION_ID);
        given(companionProfileMapper.selectList(any()))
                .willReturn(List.of(companionProfile(AuditStatus.APPROVED)));
        given(orderMapper.selectById(ORDER_ID)).willReturn(pendingOrder());
        given(orderMapper.updateById(any(CompanionOrder.class))).willReturn(1);

        OrderAcceptResultVO result = orderService.accept(ORDER_ID);

        ArgumentCaptor<CompanionOrder> captor = ArgumentCaptor.forClass(CompanionOrder.class);
        verify(orderMapper).updateById(captor.capture());
        CompanionOrder updated = captor.getValue();
        assertEquals(OrderStatus.ACCEPTED.name(), updated.getStatus());
        assertEquals(COMPANION_ID, updated.getCompanionId());
        assertNotNull(updated.getAcceptTime());

        assertEquals(OrderStatus.ACCEPTED.name(), result.getStatus());
        assertEquals("已接单", result.getStatusLabel());
        assertNotNull(result.getAcceptTime());

        ArgumentCaptor<OrderStatusLog> logCaptor = ArgumentCaptor.forClass(OrderStatusLog.class);
        verify(statusLogMapper).insert(logCaptor.capture());
        assertEquals(OrderStatus.PENDING.name(), logCaptor.getValue().getFromStatus());
        assertEquals(OrderStatus.ACCEPTED.name(), logCaptor.getValue().getToStatus());
        assertEquals(COMPANION_ID, logCaptor.getValue().getOperatorId());
        // 操作人姓名取资质快照里的真实姓名，而不是昵称
        assertEquals("李建军", logCaptor.getValue().getOperatorName());
    }

    @Test
    @DisplayName("接单：资质待审核返回 2003，连接单机会都不给")
    void acceptShouldRejectUnapprovedCompanion() {
        loginAs(RoleConstants.COMPANION, COMPANION_ID);
        given(companionProfileMapper.selectList(any()))
                .willReturn(List.of(companionProfile(AuditStatus.PENDING)));

        BusinessException ex = assertThrows(BusinessException.class, () -> orderService.accept(ORDER_ID));

        assertEquals(ResultCode.COMPANION_NOT_AUDITED.getCode(), ex.getCode());
        verify(orderMapper, never()).updateById(any(CompanionOrder.class));
    }

    /* ================================================================== */
    /* 5. 取消订单                                                         */
    /* ================================================================== */

    @Test
    @DisplayName("取消：不是下单人家属返回 3004")
    void cancelShouldRejectOtherFamily() {
        loginAs(RoleConstants.FAMILY, OTHER_FAMILY_ID);
        given(orderMapper.selectById(ORDER_ID)).willReturn(pendingOrder());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.cancel(ORDER_ID, cancelDto()));

        assertEquals(ResultCode.ORDER_NO_PERMISSION.getCode(), ex.getCode());
        verify(orderMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("取消：已接单的订单不能由家属取消，返回 3006")
    void cancelShouldRejectAcceptedOrder() {
        loginAs(RoleConstants.FAMILY, FAMILY_ID);
        CompanionOrder order = pendingOrder();
        order.setStatus(OrderStatus.ACCEPTED.name());
        given(orderMapper.selectById(ORDER_ID)).willReturn(order);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.cancel(ORDER_ID, cancelDto()));

        assertEquals(ResultCode.ORDER_CANNOT_CANCEL.getCode(), ex.getCode());
        verify(orderMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("取消：条件更新命中 0 行（并发下已被改走）同样返回 3006")
    void cancelShouldReturn3006WhenConditionalUpdateMisses() {
        loginAs(RoleConstants.FAMILY, FAMILY_ID);
        given(orderMapper.selectById(ORDER_ID)).willReturn(pendingOrder());
        given(orderMapper.update(any(), any())).willReturn(0);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.cancel(ORDER_ID, cancelDto()));

        assertEquals(ResultCode.ORDER_CANNOT_CANCEL.getCode(), ex.getCode());
        verify(statusLogMapper, never()).insert(any(OrderStatusLog.class));
    }

    @Test
    @DisplayName("取消：成功时把状态条件写进 WHERE，并记录取消原因")
    void cancelShouldSucceed() {
        loginAs(RoleConstants.FAMILY, FAMILY_ID);
        given(orderMapper.selectById(ORDER_ID)).willReturn(pendingOrder());
        given(orderMapper.update(any(), any())).willReturn(1);

        orderService.cancel(ORDER_ID, cancelDto());

        LambdaUpdateWrapper<CompanionOrder> update = captureUpdateWrapper();
        String sql = update.getSqlSegment();
        assertTrue(sql.contains("status"), "WHERE 里必须带 status 条件，否则并发下会覆盖别人的状态：" + sql);
        assertTrue(update.getSqlSet().contains("cancel_reason"), update.getSqlSet());
        assertTrue(update.getSqlSet().contains("cancel_by"), update.getSqlSet());

        ArgumentCaptor<OrderStatusLog> logCaptor = ArgumentCaptor.forClass(OrderStatusLog.class);
        verify(statusLogMapper).insert(logCaptor.capture());
        assertEquals(OrderStatus.PENDING.name(), logCaptor.getValue().getFromStatus());
        assertEquals(OrderStatus.CANCELLED.name(), logCaptor.getValue().getToStatus());
    }

    /* ================================================================== */
    /* 6. 开始 / 完成服务                                                  */
    /* ================================================================== */

    @Test
    @DisplayName("开始服务：待接单的订单返回 3002 而不是 4003（先判状态后判身份）")
    void startShouldReturn3002ForPendingOrder() {
        loginAs(RoleConstants.COMPANION, COMPANION_ID);
        given(companionProfileMapper.selectList(any()))
                .willReturn(List.of(companionProfile(AuditStatus.APPROVED)));
        given(orderMapper.selectById(ORDER_ID)).willReturn(pendingOrder());

        BusinessException ex = assertThrows(BusinessException.class, () -> orderService.start(ORDER_ID));

        // 这条断言锁的是"检查顺序"这个设计决策：
        // 待接单的订单根本没有陪诊员，回 4003「你不是本单陪诊员」毫无信息量，
        // 而 3002 才是用户真正需要知道的事
        assertEquals(ResultCode.ORDER_STATUS_ILLEGAL.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("开始服务：不是本单陪诊员返回 4003")
    void startShouldRejectOtherCompanion() {
        loginAs(RoleConstants.COMPANION, OTHER_COMPANION_ID);
        given(companionProfileMapper.selectList(any()))
                .willReturn(List.of(companionProfile(AuditStatus.APPROVED)));
        given(orderMapper.selectById(ORDER_ID)).willReturn(acceptedOrder());

        BusinessException ex = assertThrows(BusinessException.class, () -> orderService.start(ORDER_ID));

        assertEquals(ResultCode.NOT_ORDER_COMPANION.getCode(), ex.getCode());
        verify(orderMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("开始服务：成功流转到 IN_SERVICE 并写开始时间")
    void startShouldSucceed() {
        loginAs(RoleConstants.COMPANION, COMPANION_ID);
        given(companionProfileMapper.selectList(any()))
                .willReturn(List.of(companionProfile(AuditStatus.APPROVED)));
        given(orderMapper.selectById(ORDER_ID)).willReturn(acceptedOrder());
        given(orderMapper.update(any(), any())).willReturn(1);

        OrderFlowResultVO result = orderService.start(ORDER_ID);

        LambdaUpdateWrapper<CompanionOrder> update = captureUpdateWrapper();
        assertTrue(update.getSqlSet().contains("start_time"), update.getSqlSet());
        assertTrue(update.getSqlSegment().contains("status"), update.getSqlSegment());

        assertEquals(OrderStatus.IN_SERVICE.name(), result.getStatus());
        assertEquals("服务中", result.getStatusLabel());
        assertNotNull(result.getStartTime());
    }

    @Test
    @DisplayName("完成服务：状态不是服务中返回 3002")
    void completeShouldRejectWhenNotInService() {
        loginAs(RoleConstants.COMPANION, COMPANION_ID);
        given(companionProfileMapper.selectList(any()))
                .willReturn(List.of(companionProfile(AuditStatus.APPROVED)));
        given(orderMapper.selectById(ORDER_ID)).willReturn(acceptedOrder());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.complete(ORDER_ID, new OrderCompleteDTO()));

        assertEquals(ResultCode.ORDER_STATUS_ILLEGAL.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("完成服务：服务小结含用药建议返回 3007，且不写任何数据")
    void completeShouldRejectMedicalAdviceInSummary() {
        loginAs(RoleConstants.COMPANION, COMPANION_ID);
        given(companionProfileMapper.selectList(any()))
                .willReturn(List.of(companionProfile(AuditStatus.APPROVED)));
        given(orderMapper.selectById(ORDER_ID)).willReturn(inServiceOrder());

        OrderCompleteDTO dto = new OrderCompleteDTO();
        dto.setSummary("陪老人看完病，医生建议服用阿司匹林，每天一次");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.complete(ORDER_ID, dto));

        assertEquals(ResultCode.ORDER_SUMMARY_ILLEGAL.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("建议服用"), "错误提示要指出命中的词：" + ex.getMessage());
        verify(orderMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("完成服务：纯过程性小结可以通过（合规校验不能误伤正常记录）")
    void completeShouldAllowProcessOnlySummary() {
        loginAs(RoleConstants.COMPANION, COMPANION_ID);
        given(companionProfileMapper.selectList(any()))
                .willReturn(List.of(companionProfile(AuditStatus.APPROVED)));
        given(orderMapper.selectById(ORDER_ID)).willReturn(inServiceOrder());
        given(orderMapper.update(any(), any())).willReturn(1);

        OrderCompleteDTO dto = new OrderCompleteDTO();
        dto.setSummary("09:10 到达医院，全程陪同完成就诊，已协助取药，11:50 送老人回家");
        dto.setPhotos(List.of("/uploads/202609/a.jpg"));

        OrderFlowResultVO result = orderService.complete(ORDER_ID, dto);

        LambdaUpdateWrapper<CompanionOrder> update = captureUpdateWrapper();
        assertTrue(update.getSqlSet().contains("service_summary"), update.getSqlSet());
        assertTrue(update.getSqlSet().contains("service_photos"), update.getSqlSet());
        assertEquals(OrderStatus.COMPLETED.name(), result.getStatus());
        assertEquals(PaymentStatus.UNPAID.name(), result.getPaymentStatus(),
                "一期线下结算，完成时结算状态仍是未结算");
    }

    @Test
    @DisplayName("完成服务：照片序列化进 JSON 列")
    void completeShouldSerialisePhotosAsJson() throws Exception {
        loginAs(RoleConstants.COMPANION, COMPANION_ID);
        given(companionProfileMapper.selectList(any()))
                .willReturn(List.of(companionProfile(AuditStatus.APPROVED)));
        given(orderMapper.selectById(ORDER_ID)).willReturn(inServiceOrder());
        given(orderMapper.update(any(), any())).willReturn(1);

        OrderCompleteDTO dto = new OrderCompleteDTO();
        dto.setPhotos(List.of("/uploads/202609/a.jpg", "/uploads/202609/b.jpg"));

        orderService.complete(ORDER_ID, dto);

        LambdaUpdateWrapper<CompanionOrder> update = captureUpdateWrapper();
        Object json = update.getParamNameValuePairs().values().stream()
                .filter(v -> v instanceof String s && s.startsWith("["))
                .findFirst()
                .orElse(null);
        assertNotNull(json, "照片必须以 JSON 数组写入");
        assertEquals(List.of("/uploads/202609/a.jpg", "/uploads/202609/b.jpg"),
                new ObjectMapper().readValue((String) json, List.class));
    }

    @Test
    @DisplayName("完成服务：不是本单陪诊员返回 4003")
    void completeShouldRejectOtherCompanion() {
        loginAs(RoleConstants.COMPANION, OTHER_COMPANION_ID);
        given(companionProfileMapper.selectList(any()))
                .willReturn(List.of(companionProfile(AuditStatus.APPROVED)));
        given(orderMapper.selectById(ORDER_ID)).willReturn(inServiceOrder());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.complete(ORDER_ID, new OrderCompleteDTO()));

        assertEquals(ResultCode.NOT_ORDER_COMPANION.getCode(), ex.getCode());
    }

    /* ================================================================== */
    /* 7. 详情与时间线                                                     */
    /* ================================================================== */

    @Test
    @DisplayName("详情：局外人返回 3004")
    void detailShouldRejectOutsider() {
        loginAs(RoleConstants.FAMILY, OTHER_FAMILY_ID);
        given(orderMapper.selectById(ORDER_ID)).willReturn(pendingOrder());

        BusinessException ex = assertThrows(BusinessException.class, () -> orderService.detail(ORDER_ID));

        assertEquals(ResultCode.ORDER_NO_PERMISSION.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("详情：相关方看到全名、地址、备注与已解析的照片")
    void detailShouldReturnFullNamesAndPhotos() {
        loginAs(RoleConstants.FAMILY, FAMILY_ID);
        CompanionOrder order = inServiceOrder();
        order.setServiceSummary("已陪同完成就诊");
        order.setServicePhotos("[\"/uploads/202609/a.jpg\"]");
        given(orderMapper.selectById(ORDER_ID)).willReturn(order);
        given(orderReadMapper.selectEldersIgnoringLogicDelete(any())).willReturn(List.of(elderProfile()));
        given(companionProfileMapper.selectList(any()))
                .willReturn(List.of(companionProfile(AuditStatus.APPROVED)));

        OrderVO vo = orderService.detail(ORDER_ID);

        assertEquals("张德海", vo.getElderName());
        assertEquals("李建军", vo.getCompanionName());
        assertEquals("海南省海口市秀英区秀华路19号 门诊大楼3楼", vo.getAddress());
        assertEquals("老人听力不好，请大声沟通", vo.getRemark());
        assertEquals(List.of("/uploads/202609/a.jpg"), vo.getServicePhotos());
        assertEquals("未结算", vo.getPaymentStatusLabel());
        assertEquals(ELDER_ID, vo.getElderId());
    }

    @Test
    @DisplayName("详情：老人档案已被逻辑删除，仍要能从物理行里补出姓名")
    void detailShouldStillShowElderNameAfterProfileDeleted() {
        loginAs(RoleConstants.ADMIN, 1L);
        given(orderMapper.selectById(ORDER_ID)).willReturn(pendingOrder());
        // OrderReadMapper 刻意不过滤 deleted —— 这正是它存在的理由
        given(orderReadMapper.selectEldersIgnoringLogicDelete(any())).willReturn(List.of(elderProfile()));

        OrderVO vo = orderService.detail(ORDER_ID);

        assertEquals("张德海", vo.getElderName(), "M3 删除档案时保留了物理行，历史订单要能继续显示姓名");
    }

    @Test
    @DisplayName("详情：服务照片 JSON 脏数据不能让整个详情打不开")
    void detailShouldTolerateBrokenPhotosJson() {
        loginAs(RoleConstants.FAMILY, FAMILY_ID);
        CompanionOrder order = pendingOrder();
        order.setServicePhotos("这不是 JSON");
        given(orderMapper.selectById(ORDER_ID)).willReturn(order);
        given(orderReadMapper.selectEldersIgnoringLogicDelete(any())).willReturn(List.of(elderProfile()));

        OrderVO vo = orderService.detail(ORDER_ID);

        assertNull(vo.getServicePhotos());
        assertEquals(ORDER_ID, vo.getId());
    }

    @Test
    @DisplayName("详情：就诊老人本人可以看自己的订单（老人账号只读但能读）")
    void detailShouldAllowOrderElder() {
        loginAs(RoleConstants.ELDER, ELDER_USER_ID);
        given(orderMapper.selectById(ORDER_ID)).willReturn(pendingOrder());
        given(orderReadMapper.selectEldersIgnoringLogicDelete(any())).willReturn(List.of(elderProfile()));

        OrderVO vo = orderService.detail(ORDER_ID);

        assertEquals(ORDER_ID, vo.getId());
    }

    @Test
    @DisplayName("详情：与就诊老人无关的其他老人账号返回 3004")
    void detailShouldRejectUnrelatedElder() {
        loginAs(RoleConstants.ELDER, 999L);
        given(orderMapper.selectById(ORDER_ID)).willReturn(pendingOrder());
        given(orderReadMapper.selectEldersIgnoringLogicDelete(any())).willReturn(List.of(elderProfile()));

        BusinessException ex = assertThrows(BusinessException.class, () -> orderService.detail(ORDER_ID));

        assertEquals(ResultCode.ORDER_NO_PERMISSION.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("时间线：局外人返回 3004")
    void timelineShouldRejectOutsider() {
        loginAs(RoleConstants.FAMILY, OTHER_FAMILY_ID);
        given(orderMapper.selectById(ORDER_ID)).willReturn(pendingOrder());

        BusinessException ex = assertThrows(BusinessException.class, () -> orderService.timeline(ORDER_ID));

        assertEquals(ResultCode.ORDER_NO_PERMISSION.getCode(), ex.getCode());
        verify(statusLogMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("时间线：按发生时间升序返回，并带上操作人角色")
    void timelineShouldReturnOrderedNodes() {
        loginAs(RoleConstants.FAMILY, FAMILY_ID);
        given(orderMapper.selectById(ORDER_ID)).willReturn(pendingOrder());
        OrderStatusLog first = statusLog(OrderStatus.PENDING, RoleConstants.FAMILY, "家属01", "下单成功");
        OrderStatusLog second = statusLog(OrderStatus.ACCEPTED, RoleConstants.COMPANION, "李建军", "已接单");
        given(statusLogMapper.selectList(any())).willReturn(List.of(first, second));

        List<OrderTimelineVO> timeline = orderService.timeline(ORDER_ID);

        assertEquals(2, timeline.size());
        assertEquals("待接单", timeline.get(0).getStatusLabel());
        assertEquals("已接单", timeline.get(1).getStatusLabel());
        assertEquals("李建军", timeline.get(1).getOperatorName());
        assertEquals(RoleConstants.COMPANION, timeline.get(1).getOperatorRole());
    }

    /* ================================================================== */
    /* 8. 拒单                                                             */
    /* ================================================================== */

    @Test
    @DisplayName("拒单：不改变订单状态，也不写状态日志")
    void rejectShouldNotTouchOrderStatus() {
        loginAs(RoleConstants.COMPANION, COMPANION_ID);
        given(companionProfileMapper.selectList(any()))
                .willReturn(List.of(companionProfile(AuditStatus.APPROVED)));
        given(orderMapper.selectById(ORDER_ID)).willReturn(pendingOrder());
        given(rejectLogMapper.selectCount(any())).willReturn(0L);

        OrderRejectDTO dto = new OrderRejectDTO();
        dto.setReason("当天已有其他订单");

        orderService.reject(ORDER_ID, dto);

        ArgumentCaptor<OrderRejectLog> captor = ArgumentCaptor.forClass(OrderRejectLog.class);
        verify(rejectLogMapper).insert(captor.capture());
        assertEquals(ORDER_ID, captor.getValue().getOrderId());
        assertEquals(COMPANION_ID, captor.getValue().getCompanionId());
        assertEquals("当天已有其他订单", captor.getValue().getReason());

        // 拒单没改状态，就不该往状态时间线里塞记录
        verify(orderMapper, never()).update(any(), any());
        verify(orderMapper, never()).updateById(any(CompanionOrder.class));
        verify(statusLogMapper, never()).insert(any(OrderStatusLog.class));
    }

    @Test
    @DisplayName("拒单：重复拒同一单返回 409")
    void rejectShouldRejectDuplicate() {
        loginAs(RoleConstants.COMPANION, COMPANION_ID);
        given(companionProfileMapper.selectList(any()))
                .willReturn(List.of(companionProfile(AuditStatus.APPROVED)));
        given(orderMapper.selectById(ORDER_ID)).willReturn(pendingOrder());
        given(rejectLogMapper.selectCount(any())).willReturn(1L);

        OrderRejectDTO dto = new OrderRejectDTO();
        dto.setReason("时间冲突");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.reject(ORDER_ID, dto));

        assertEquals(ResultCode.CONFLICT.getCode(), ex.getCode());
        verify(rejectLogMapper, never()).insert(any(OrderRejectLog.class));
    }

    @Test
    @DisplayName("拒单：已被接走的单子不能再拒，返回 3002")
    void rejectShouldRejectNonPendingOrder() {
        loginAs(RoleConstants.COMPANION, COMPANION_ID);
        given(companionProfileMapper.selectList(any()))
                .willReturn(List.of(companionProfile(AuditStatus.APPROVED)));
        given(orderMapper.selectById(ORDER_ID)).willReturn(acceptedOrder());

        OrderRejectDTO dto = new OrderRejectDTO();
        dto.setReason("时间冲突");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.reject(ORDER_ID, dto));

        assertEquals(ResultCode.ORDER_STATUS_ILLEGAL.getCode(), ex.getCode());
    }

    /* ================================================================== */
    /* 9. 订单不存在                                                       */
    /* ================================================================== */

    @Test
    @DisplayName("订单不存在统一返回 3001")
    void shouldReturn3001WhenOrderMissing() {
        loginAs(RoleConstants.FAMILY, FAMILY_ID);
        given(orderMapper.selectById(ORDER_ID)).willReturn(null);

        assertEquals(ResultCode.ORDER_NOT_FOUND.getCode(),
                assertThrows(BusinessException.class, () -> orderService.detail(ORDER_ID)).getCode());
        assertEquals(ResultCode.ORDER_NOT_FOUND.getCode(),
                assertThrows(BusinessException.class,
                        () -> orderService.cancel(ORDER_ID, cancelDto())).getCode());
    }

    /* ================================================================== */
    /* 测试夹具                                                            */
    /* ================================================================== */

    private void loginAs(String role, Long userId) {
        LoginUser loginUser = new LoginUser(userId, "tester" + userId, role, 0,
                "jti-test", System.currentTimeMillis() + 60000);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, loginUser.authorities()));
    }

    /** 下单链路上必需的桩：老人归属通过 + Redis 给出序列号 + insert 回填主键 */
    private void stubCreateHappyPath() {
        given(elderService.requireAccessible(ELDER_ID)).willReturn(elderProfile());
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.increment(anyString())).willReturn(7L);
        given(orderMapper.insert(any(CompanionOrder.class))).willAnswer(invocation -> {
            invocation.getArgument(0, CompanionOrder.class).setId(ORDER_ID);
            return 1;
        });
    }

    private CompanionOrder captureInsertedOrder() {
        ArgumentCaptor<CompanionOrder> captor = ArgumentCaptor.forClass(CompanionOrder.class);
        verify(orderMapper).insert(captor.capture());
        return captor.getValue();
    }

    /**
     * 抓取传给 {@code update(null, wrapper)} 的更新包装器。
     *
     * <p>必须按 {@link LambdaUpdateWrapper} 类型抓：{@code getSqlSet()} 与
     * {@code getParamNameValuePairs()} 是 {@code Update} 接口上的方法，
     * 按父接口 {@code Wrapper} 抓会取不到。</p>
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private LambdaUpdateWrapper<CompanionOrder> captureUpdateWrapper() {
        ArgumentCaptor<LambdaUpdateWrapper> captor = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(orderMapper).update(any(), captor.capture());
        return captor.getValue();
    }

    /** 同理，查询包装器要按 {@link LambdaQueryWrapper} 类型抓 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private LambdaQueryWrapper<CompanionOrder> captureQueryWrapper() {
        ArgumentCaptor<LambdaQueryWrapper> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(orderMapper).selectPage(any(), captor.capture());
        return captor.getValue();
    }

    private OrderCreateDTO createDto(LocalDateTime visitTime, BigDecimal fee) {
        OrderCreateDTO dto = new OrderCreateDTO();
        dto.setElderId(ELDER_ID);
        dto.setHospital("海南省人民医院");
        dto.setDepartment("心血管内科");
        dto.setVisitTime(visitTime);
        dto.setAddress("海南省海口市秀英区秀华路19号 门诊大楼3楼");
        dto.setRemark("老人听力不好，请大声沟通");
        dto.setFee(fee);
        return dto;
    }

    private OrderCancelDTO cancelDto() {
        OrderCancelDTO dto = new OrderCancelDTO();
        dto.setReason("老人临时身体不适");
        return dto;
    }

    private CompanionOrder pendingOrder() {
        return order(ORDER_ID, OrderStatus.PENDING, null);
    }

    private CompanionOrder acceptedOrder() {
        return order(ORDER_ID, OrderStatus.ACCEPTED, COMPANION_ID);
    }

    private CompanionOrder inServiceOrder() {
        return order(ORDER_ID, OrderStatus.IN_SERVICE, COMPANION_ID);
    }

    private CompanionOrder order(Long id, OrderStatus status, Long companionId) {
        CompanionOrder order = new CompanionOrder();
        order.setId(id);
        order.setOrderNo("NL20260915000001");
        order.setFamilyId(FAMILY_ID);
        order.setElderId(ELDER_ID);
        order.setCompanionId(companionId);
        order.setHospital("海南省人民医院");
        order.setDepartment("心血管内科");
        order.setVisitTime(LocalDateTime.now().plusDays(3));
        order.setAddress("海南省海口市秀英区秀华路19号 门诊大楼3楼");
        order.setRemark("老人听力不好，请大声沟通");
        order.setStatus(status.name());
        order.setFee(new BigDecimal("128.00"));
        order.setPaymentStatus(PaymentStatus.UNPAID.name());
        order.setVersion(0);
        order.setArbitrateFlag(0);
        return order;
    }

    /** 档案：姓名/出生日期齐全，user_id 指向老人账号 */
    private ElderProfile elderProfile() {
        ElderProfile elder = new ElderProfile();
        elder.setId(ELDER_ID);
        elder.setUserId(ELDER_USER_ID);
        elder.setName("张德海");
        elder.setGender("MALE");
        elder.setBirthDate(LocalDate.of(1948, 3, 12));
        return elder;
    }

    private CompanionProfile companionProfile(AuditStatus auditStatus) {
        CompanionProfile profile = new CompanionProfile();
        profile.setId(601L);
        profile.setUserId(COMPANION_ID);
        profile.setRealName("李建军");
        profile.setAuditStatus(auditStatus.name());
        return profile;
    }

    private OrderRejectLog rejectLog(Long orderId) {
        OrderRejectLog row = new OrderRejectLog();
        row.setOrderId(orderId);
        row.setCompanionId(COMPANION_ID);
        return row;
    }

    private OrderStatusLog statusLog(OrderStatus to, String role, String operatorName, String remark) {
        OrderStatusLog row = new OrderStatusLog();
        row.setOrderId(ORDER_ID);
        row.setToStatus(to.name());
        row.setOperatorRole(role);
        row.setOperatorName(operatorName);
        row.setRemark(remark);
        row.setOperateTime(LocalDateTime.now());
        return row;
    }

    @SafeVarargs
    private Page<CompanionOrder> pageOf(CompanionOrder... orders) {
        Page<CompanionOrder> page = new Page<>(1, 10, orders.length);
        page.setRecords(List.of(orders));
        return page;
    }

    /** 下一个工作日的某个整点（跳过周末，保证命中的是基础价） */
    private static LocalDateTime nextWeekdayAt(int hour) {
        LocalDate date = LocalDate.now().plusDays(1);
        while (date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY) {
            date = date.plusDays(1);
        }
        return date.atTime(hour, 0);
    }

    /** 下一个周末的某个整点（命中加价规则） */
    private static LocalDateTime nextWeekendAt(int hour) {
        LocalDate date = LocalDate.now().plusDays(1);
        while (date.getDayOfWeek() != DayOfWeek.SATURDAY && date.getDayOfWeek() != DayOfWeek.SUNDAY) {
            date = date.plusDays(1);
        }
        return date.atTime(hour, 0);
    }
}

package org.company.nianglin.service;

import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.company.nianglin.common.ResultCode;
import org.company.nianglin.constant.ComplaintStatus;
import org.company.nianglin.constant.MessageType;
import org.company.nianglin.constant.OrderStatus;
import org.company.nianglin.constant.RoleConstants;
import org.company.nianglin.dto.ComplaintCreateDTO;
import org.company.nianglin.dto.ComplaintQuery;
import org.company.nianglin.entity.CompanionOrder;
import org.company.nianglin.entity.Complaint;
import org.company.nianglin.entity.OrderReview;
import org.company.nianglin.entity.SysUser;
import org.company.nianglin.exception.BusinessException;
import org.company.nianglin.mapper.ComplaintMapper;
import org.company.nianglin.mapper.OrderReviewMapper;
import org.company.nianglin.mapper.SysUserMapper;
import org.company.nianglin.security.LoginUser;
import org.company.nianglin.service.impl.ComplaintServiceImpl;
import org.company.nianglin.service.impl.GovernanceTimeoutServiceImpl;
import org.company.nianglin.service.support.GovernanceTimeoutPolicy;
import org.company.nianglin.service.support.UserNameResolver;
import org.company.nianglin.support.MybatisLambdaCache;
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
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * P1-6 · 投诉超时升级 + 申诉占用按 type 隔离。
 *
 * <p>两个独立的治理闭环缺口：</p>
 * <ol>
 *   <li><b>「处理中」可以永久停留</b>：管理员点了「处理中」之后不点结案，
 *       投诉就永远挂在处理中，用户看到的是「正在处理」—— 一个可以无限期成立的承诺。</li>
 *   <li><b>申诉被普通投诉占位</b>：家属先就同一订单提了普通投诉，
 *       陪诊员对差评的申诉就被 409 挡住 —— 而申诉正是 E4 的核心能力。</li>
 * </ol>
 *
 * @author 银龄伴诊团队
 * @since closure-2026-11
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("P1-6 · 投诉超时升级 + 申诉占用按 type 隔离")
class GovernanceTimeoutTest {

    private static final Long ORDER_ID = 1033L;
    private static final Long FAMILY_ID = 103L;
    private static final Long COMPANION_ID = 309L;
    private static final Long REVIEW_ID = 30003L;
    private static final Long COMPLAINT_ID = 5001L;
    private static final Long ADMIN_ID = 1L;

    @Mock
    private ComplaintMapper complaintMapper;

    @Mock
    private SysUserMapper sysUserMapper;

    @Mock
    private OrderService orderService;

    @Mock
    private MessageService messageService;

    @Mock
    private UserNameResolver userNameResolver;

    @Mock
    private OrderReviewMapper orderReviewMapper;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private ComplaintServiceImpl complaintService;
    private GovernanceTimeoutServiceImpl timeoutService;

    @BeforeEach
    void setUp() {
        MybatisLambdaCache.warmUp();
        complaintService = new ComplaintServiceImpl(complaintMapper, sysUserMapper, orderService,
                messageService, userNameResolver, new ObjectMapper(), orderReviewMapper);
        timeoutService = new GovernanceTimeoutServiceImpl(complaintMapper, sysUserMapper,
                messageService, redisTemplate);
        ReflectionTestUtils.setField(complaintService, "appealDeadlineDays", 15);
        ReflectionTestUtils.setField(timeoutService, "complaintTimeoutDays", 3);
        ReflectionTestUtils.setField(timeoutService, "scanBatch", 200);
        loginAs(RoleConstants.COMPANION, COMPANION_ID);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    /* ================================================================== */
    /* A · 申诉占用按 type 隔离                                             */
    /* ================================================================== */

    @Test
    @DisplayName("申诉 · 同订单已有普通投诉时仍应成功（申诉不被普通投诉占位）")
    void appealShouldNotBeBlockedByOrdinaryComplaint() {
        given(orderService.requireInvolved(ORDER_ID)).willReturn(order());
        given(orderReviewMapper.selectById(REVIEW_ID)).willReturn(review(LocalDateTime.now().minusDays(2)));
        given(complaintMapper.countOpenAppealByReviewId(REVIEW_ID)).willReturn(0L);

        complaintService.create(appealDto());

        // 关键断言：申诉路径**根本不查**「同订单未结案投诉」——
        // 查了就说明两条路径互相占位，家属的普通投诉会把陪诊员的申诉挡掉
        verify(complaintMapper, never()).selectCount(any());
        verify(complaintMapper).insert(any(Complaint.class));
    }

    @Test
    @DisplayName("普通投诉 · 同订单已有未结案普通投诉时仍应 409（保护现状）")
    void ordinaryComplaintShouldStillBeBlockedByOpenComplaint() {
        loginAs(RoleConstants.FAMILY, FAMILY_ID);
        given(orderService.requireInvolved(ORDER_ID)).willReturn(order());
        given(complaintMapper.selectCount(any())).willReturn(1L);

        ComplaintCreateDTO dto = new ComplaintCreateDTO();
        dto.setOrderId(ORDER_ID);
        dto.setType("LATE");
        dto.setContent("陪诊员迟到四十分钟，老人错过了取号。");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> complaintService.create(dto));

        assertEquals(ResultCode.CONFLICT.getCode(), ex.getCode());
        verify(complaintMapper, never()).insert(any(Complaint.class));
    }

    @Test
    @DisplayName("普通投诉 · 占用检查要排除申诉（申诉不占普通投诉的位）")
    void ordinaryComplaintOpenCheckShouldExcludeAppeals() {
        loginAs(RoleConstants.FAMILY, FAMILY_ID);
        given(orderService.requireInvolved(ORDER_ID)).willReturn(order());
        given(complaintMapper.selectCount(any())).willReturn(0L);

        ComplaintCreateDTO dto = new ComplaintCreateDTO();
        dto.setOrderId(ORDER_ID);
        dto.setType("LATE");
        dto.setContent("陪诊员迟到四十分钟，老人错过了取号。");
        complaintService.create(dto);

        ArgumentCaptor<Wrapper<Complaint>> captor = wrapperCaptor();
        verify(complaintMapper).selectCount(captor.capture());
        AbstractWrapper<?, ?, ?> wrapper = (AbstractWrapper<?, ?, ?>) captor.getValue();
        wrapper.getSqlSegment();
        String params = String.valueOf(wrapper.getParamNameValuePairs().values());
        assertTrue(params.contains("REVIEW_APPEAL"),
                "普通投诉的占用检查必须排除申诉类型：" + params);
    }

    /* ================================================================== */
    /* B · 超时判定（纯策略）                                              */
    /* ================================================================== */

    @Test
    @DisplayName("策略 · 处理中超 3 天 → 超时天数 = 实际超出的天数")
    void policyShouldComputeOverdueDays() {
        LocalDateTime now = LocalDateTime.now();
        Integer days = GovernanceTimeoutPolicy.overdueDays(
                ComplaintStatus.PROCESSING.name(), now.minusDays(5), now.minusDays(9), now, 3);
        assertEquals(5, days);
    }

    @Test
    @DisplayName("策略 · 处理中未超 3 天 → 不超时")
    void policyShouldNotMarkRecentProcessingAsOverdue() {
        LocalDateTime now = LocalDateTime.now();
        assertNull(GovernanceTimeoutPolicy.overdueDays(
                ComplaintStatus.PROCESSING.name(), now.minusDays(2), now.minusDays(2), now, 3));
    }

    @Test
    @DisplayName("策略 · 已结案 / 待处理 → 永不算超时")
    void policyShouldIgnoreNonProcessingStatuses() {
        LocalDateTime now = LocalDateTime.now();
        assertNull(GovernanceTimeoutPolicy.overdueDays(
                ComplaintStatus.RESOLVED.name(), now.minusDays(30), now.minusDays(30), now, 3));
        assertNull(GovernanceTimeoutPolicy.overdueDays(
                ComplaintStatus.PENDING.name(), now.minusDays(30), now.minusDays(30), now, 3));
    }

    /* ================================================================== */
    /* C · 列表里的超时标记                                                */
    /* ================================================================== */

    @Test
    @DisplayName("列表 · 处理中超 3 天的投诉 overdue=true 且带出天数")
    void listShouldMarkOverdueComplaint() {
        givenPage(processingComplaint(LocalDateTime.now().minusDays(4)));

        var vo = complaintService.myList(new ComplaintQuery()).getRecords().get(0);

        assertTrue(vo.getOverdue());
        assertEquals(4, vo.getOverdueDays());
    }

    @Test
    @DisplayName("列表 · 处理中未超 3 天的投诉 overdue=false")
    void listShouldNotMarkRecentComplaint() {
        givenPage(processingComplaint(LocalDateTime.now().minusDays(1)));

        var vo = complaintService.myList(new ComplaintQuery()).getRecords().get(0);

        assertFalse(vo.getOverdue());
        assertNull(vo.getOverdueDays());
    }

    /* ================================================================== */
    /* D · 扫描与提醒                                                      */
    /* ================================================================== */

    @Test
    @DisplayName("扫描 · 超时投诉应提醒管理员，且不改状态、不改 update_time")
    void scanShouldNotifyAdminsWithoutTouchingState() {
        given(complaintMapper.selectList(any()))
                .willReturn(List.of(processingComplaint(LocalDateTime.now().minusDays(4))));
        given(sysUserMapper.selectList(any())).willReturn(List.of(adminUser()));
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.setIfAbsent(any(), any(), any(Duration.class))).willReturn(true);

        int notified = timeoutService.scanOverdueComplaints();

        assertEquals(1, notified);
        verify(messageService).send(eq(ADMIN_ID), eq(MessageType.COMPLAINT_TIMEOUT),
                eq(COMPLAINT_ID), any());
        // 只升级可见性：既不改状态，也不碰 update_time
        verify(complaintMapper, never()).update(any(), any());
        verify(complaintMapper, never()).updateById(any(Complaint.class));
    }

    @Test
    @DisplayName("扫描 · 同一档位只提醒一次（第二次扫描不再打扰管理员）")
    void scanShouldRemindOnlyOncePerTier() {
        given(complaintMapper.selectList(any()))
                .willReturn(List.of(processingComplaint(LocalDateTime.now().minusDays(4))));
        given(sysUserMapper.selectList(any())).willReturn(List.of(adminUser()));
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        // 第一次抢到（未提醒过），第二次抢不到（这一档提醒过了）
        given(valueOperations.setIfAbsent(any(), any(), any(Duration.class)))
                .willReturn(true, false);

        assertEquals(1, timeoutService.scanOverdueComplaints());
        assertEquals(0, timeoutService.scanOverdueComplaints());

        verify(messageService, times(1)).send(eq(ADMIN_ID), eq(MessageType.COMPLAINT_TIMEOUT),
                eq(COMPLAINT_ID), any());
    }

    /* ================================================================== */

    private void givenPage(Complaint complaint) {
        Page<Complaint> page = new Page<>(1, 10);
        page.setRecords(List.of(complaint));
        page.setTotal(1);
        given(complaintMapper.selectPage(any(), any())).willReturn(page);
        given(userNameResolver.resolveAll(any())).willReturn(Map.of());
    }

    private Complaint processingComplaint(LocalDateTime updateTime) {
        Complaint complaint = new Complaint();
        complaint.setId(COMPLAINT_ID);
        complaint.setOrderId(ORDER_ID);
        complaint.setOrderNo("NL20260822000033");
        complaint.setComplainantId(FAMILY_ID);
        complaint.setTargetUserId(COMPANION_ID);
        complaint.setType("LATE");
        complaint.setContent("陪诊员迟到四十分钟");
        complaint.setStatus(ComplaintStatus.PROCESSING.name());
        complaint.setCreateTime(updateTime.minusDays(2));
        complaint.setUpdateTime(updateTime);
        return complaint;
    }

    private SysUser adminUser() {
        SysUser user = new SysUser();
        user.setId(ADMIN_ID);
        user.setRole(RoleConstants.ADMIN);
        return user;
    }

    private ComplaintCreateDTO appealDto() {
        ComplaintCreateDTO dto = new ComplaintCreateDTO();
        dto.setOrderId(ORDER_ID);
        dto.setType("REVIEW_APPEAL");
        dto.setReviewId(REVIEW_ID);
        dto.setContent("评价与实际情况不符，我有当日 08:20 的打卡记录。");
        return dto;
    }

    private CompanionOrder order() {
        CompanionOrder order = new CompanionOrder();
        order.setId(ORDER_ID);
        order.setOrderNo("NL20260822000033");
        order.setFamilyId(FAMILY_ID);
        order.setElderId(403L);
        order.setCompanionId(COMPANION_ID);
        order.setStatus(OrderStatus.REVIEWED.name());
        return order;
    }

    private OrderReview review(LocalDateTime createTime) {
        OrderReview review = new OrderReview();
        review.setId(REVIEW_ID);
        review.setOrderId(ORDER_ID);
        review.setCompanionId(COMPANION_ID);
        review.setFamilyId(FAMILY_ID);
        review.setScore(2);
        review.setCreateTime(createTime);
        review.setIsValid(1);
        return review;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ArgumentCaptor<Wrapper<Complaint>> wrapperCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(Wrapper.class);
    }

    private void loginAs(String role, Long userId) {
        LoginUser loginUser = new LoginUser(userId, "tester", role, 0, "jti",
                System.currentTimeMillis() + 60000);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, loginUser.authorities()));
    }
}

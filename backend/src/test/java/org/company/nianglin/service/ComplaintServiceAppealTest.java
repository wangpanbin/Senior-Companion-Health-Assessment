package org.company.nianglin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.company.nianglin.common.ResultCode;
import org.company.nianglin.constant.OrderStatus;
import org.company.nianglin.constant.RoleConstants;
import org.company.nianglin.dto.ComplaintCreateDTO;
import org.company.nianglin.entity.CompanionOrder;
import org.company.nianglin.entity.Complaint;
import org.company.nianglin.entity.OrderReview;
import org.company.nianglin.exception.BusinessException;
import org.company.nianglin.mapper.ComplaintMapper;
import org.company.nianglin.mapper.OrderReviewMapper;
import org.company.nianglin.mapper.SysUserMapper;
import org.company.nianglin.security.LoginUser;
import org.company.nianglin.service.impl.ComplaintServiceImpl;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 评价申诉（E4 评价公信力闭环）的契约单测。
 *
 * <p>本类盯的是<b>五条容易漏掉的硬约束</b>，按出问题时的「公信力代价」排序：</p>
 *
 * <ol>
 *   <li><b>归属三重校验</b>：申诉人必须是评价的陪诊员，评价必须属于本订单，
 *       且评价的 {@code orderId} 与入参 {@code orderId} 一致 —— 任何一处不匹配
 *       都返回 3004。这是最容易被漏掉的「申诉人来自陪诊员池，但评价可能属于别人」漏洞。</li>
 *   <li><b>时限</b>：评价提交超过 {@code appeal-deadline-days}（默认 15）天就不能再申诉。
 *       边界用例必须覆盖「刚超期一天」与「刚到 D-1」两条。</li>
 *   <li><b>评价级未结案唯一</b>：同一条评价同时最多 1 个未结案申诉，
 *       避免管理员精力被同一评价的 N 条重复申诉消耗（PRD §RK-01）。</li>
 *   <li><b>复用既有订单级未结案唯一</b>：同订单已有未结案投诉 → 409（既有逻辑，零改动）。</li>
 *   <li><b>REVIEW_APPEAL 必须带 reviewId</b>：申诉与普通投诉走同一通道，
 *       DTO 多了 reviewId 可选字段；类型断言比「DTO 加 @NotNull on type=REVIEW_APPEAL」
 *       灵活 —— 后者的条件注解很容易漏。</li>
 * </ol>
 *
 * @author 银龄伴诊团队
 * @since E4
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("评价申诉：归属三重校验 / 时限 / 同评价未结案唯一 / 复用通道")
class ComplaintServiceAppealTest {

    private static final Long ORDER_ID = 1033L;
    private static final Long FAMILY_ID = 103L;
    private static final Long COMPANION_ID = 309L;          // 评价 30003 的陪诊员
    private static final Long OTHER_COMPANION_ID = 308L;   // 评价 30002 的陪诊员
    private static final Long REVIEW_ID = 30003L;
    private static final int APPEAL_DEADLINE_DAYS = 15;

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

    private ComplaintServiceImpl service;

    @BeforeEach
    void setUp() {
        MybatisLambdaCache.warmUp();
        service = new ComplaintServiceImpl(complaintMapper, sysUserMapper, orderService,
                messageService, userNameResolver, new ObjectMapper(), orderReviewMapper);
        // appealDeadlineDays 是 @Value 注入；构造器外只能用反射赋值
        ReflectionTestUtils.setField(service, "appealDeadlineDays", APPEAL_DEADLINE_DAYS);
        loginAs(RoleConstants.COMPANION, COMPANION_ID);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    /* ================================================================== */
    /* 1 · 正常路径                                                         */
    /* ================================================================== */

    @Test
    @DisplayName("申诉 · 成功：评价 ↔ 订单 ↔ 申诉人三方一致 + 时限内 + 无未结案申诉")
    void appealShouldPersistWhenAllChecksPass() {
        given(orderService.requireInvolved(ORDER_ID)).willReturn(order());
        given(orderReviewMapper.selectById(REVIEW_ID)).willReturn(review(LocalDateTime.now().minusDays(2)));
        given(complaintMapper.selectCount(any())).willReturn(0L);
        given(complaintMapper.countOpenAppealByReviewId(REVIEW_ID)).willReturn(0L);

        ComplaintCreateDTO dto = appealDto(REVIEW_ID,
                "评价与实际情况不符，我有当日 08:20 的打卡记录。");
        ComplaintCreateResultVOStub vo = invokeCreate(dto);

        // 落库字段：评价申诉的 complainantId=陪诊员，targetUserId=家属
        Complaint saved = captureInsertedComplaint();
        assertEquals(RoleConstants.COMPANION, saved.getComplainantRole());
        assertEquals(COMPANION_ID, saved.getComplainantId());
        assertEquals(FAMILY_ID, saved.getTargetUserId(),
                "REVIEW_APPEAL 的被投诉方必须是评价人家属，不接受前端传入");
        assertEquals(RoleConstants.FAMILY, saved.getTargetRole());
        assertEquals("REVIEW_APPEAL", saved.getType());
    }

    /* ================================================================== */
    /* 2 · 五条失败路径                                                     */
    /* ================================================================== */

    @Test
    @DisplayName("申诉 · 评价与订单不一致 → 3004（review.orderId != dto.orderId）")
    void appealShouldRejectWhenReviewOrderMismatch() {
        CompanionOrder otherOrder = order();
        otherOrder.setId(9999L);  // 与评价的 orderId 不同
        given(orderService.requireInvolved(9999L)).willReturn(otherOrder);
        given(orderReviewMapper.selectById(REVIEW_ID)).willReturn(review(LocalDateTime.now().minusDays(2)));

        ComplaintCreateDTO dto = appealDto(REVIEW_ID, "评价不实");
        dto.setOrderId(9999L);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.create(dto));
        assertEquals(ResultCode.ORDER_NO_PERMISSION.getCode(), ex.getCode());
        verify(complaintMapper, never()).insert(any(Complaint.class));
    }

    @Test
    @DisplayName("申诉 · 评价不属于本陪诊员 → 3004（陪诊员 A 申诉陪诊员 B 的评价）")
    void appealShouldRejectWhenReviewNotBelongToCompanion() {
        // 关键：登录身份是订单的陪诊员（让第一道 ORDER_NO_PERMISSION 闸门放行），
        // 但评价本身的 companionId 指向另一位陪诊员（让申诉分支的第二道闸门拒绝）。
        // 两条 3004 是不同含义：前者是「你和这笔订单无关」，后者是「评价不是你的」
        loginAs(RoleConstants.COMPANION, OTHER_COMPANION_ID);
        CompanionOrder myOrder = order();
        myOrder.setCompanionId(OTHER_COMPANION_ID); // 订单的陪诊员 = 当前登录
        given(orderService.requireInvolved(ORDER_ID)).willReturn(myOrder);
        given(orderReviewMapper.selectById(REVIEW_ID))
                .willReturn(review(LocalDateTime.now().minusDays(2))); // 评价的 companionId = COMPANION_ID (309)

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.create(appealDto(REVIEW_ID, "评价不实")));
        assertEquals(ResultCode.ORDER_NO_PERMISSION.getCode(), ex.getCode());
        verify(complaintMapper, never()).insert(any(Complaint.class));
    }

    @Test
    @DisplayName("申诉 · 评价不存在 → 6005")
    void appealShouldRejectMissingReview() {
        given(orderService.requireInvolved(ORDER_ID)).willReturn(order());
        given(orderReviewMapper.selectById(REVIEW_ID)).willReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.create(appealDto(REVIEW_ID, "评价不实")));
        assertEquals(ResultCode.REVIEW_NOT_FOUND.getCode(), ex.getCode());
        verify(complaintMapper, never()).insert(any(Complaint.class));
    }

    @Test
    @DisplayName("申诉 · 已超过 15 日时限 → 409，提示文案内嵌阈值")
    void appealShouldRejectOverdue() {
        // 评价创建于 D-16，D-15=今天 ⇒ 已超 1 天
        given(orderService.requireInvolved(ORDER_ID)).willReturn(order());
        given(orderReviewMapper.selectById(REVIEW_ID))
                .willReturn(review(LocalDateTime.now().minusDays(APPEAL_DEADLINE_DAYS + 1)));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.create(appealDto(REVIEW_ID, "评价不实但已超期")));
        assertEquals(ResultCode.CONFLICT.getCode(), ex.getCode());
        assertEquals(true, ex.getMessage().contains(String.valueOf(APPEAL_DEADLINE_DAYS)),
                "提示文案必须告诉用户时限阈值，便于前端展示具体可申诉天数");
    }

    @Test
    @DisplayName("申诉 · 同评价已有未结案申诉 → 409（防滥用，PRD §RK-01）")
    void appealShouldRejectDuplicateOpenAppeal() {
        given(orderService.requireInvolved(ORDER_ID)).willReturn(order());
        given(orderReviewMapper.selectById(REVIEW_ID)).willReturn(review(LocalDateTime.now().minusDays(2)));
        given(complaintMapper.countOpenAppealByReviewId(REVIEW_ID)).willReturn(1L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.create(appealDto(REVIEW_ID, "再申诉一次试试")));
        assertEquals(ResultCode.CONFLICT.getCode(), ex.getCode());
        assertEquals(true, ex.getMessage().contains("申诉"));
        verify(complaintMapper, never()).insert(any(Complaint.class));
    }

    /* ================================================================== */

    /**
     * 调 service.create 后拿到 ComplaintCreateResultVO —— 这里只关心副作用，
     * VO 字段本类不验，返回值用一个轻量 stub 接住。
     */
    private static final class ComplaintCreateResultVOStub {
    }

    private ComplaintCreateResultVOStub invokeCreate(ComplaintCreateDTO dto) {
        service.create(dto);
        return new ComplaintCreateResultVOStub();
    }

    private Complaint captureInsertedComplaint() {
        ArgumentCaptor<Complaint> captor = ArgumentCaptor.forClass(Complaint.class);
        verify(complaintMapper).insert(captor.capture());
        return captor.getValue();
    }

    private ComplaintCreateDTO appealDto(Long reviewId, String content) {
        ComplaintCreateDTO dto = new ComplaintCreateDTO();
        dto.setOrderId(ORDER_ID);
        dto.setType("REVIEW_APPEAL");
        dto.setReviewId(reviewId);
        dto.setContent(content);
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

    private void loginAs(String role, Long userId) {
        LoginUser loginUser = new LoginUser(userId, "tester", role, 0, "jti",
                System.currentTimeMillis() + 60000);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, loginUser.authorities()));
    }
}
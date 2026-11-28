package org.company.nianglin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.company.nianglin.common.ResultCode;
import org.company.nianglin.constant.MessageType;
import org.company.nianglin.constant.OrderStatus;
import org.company.nianglin.constant.RoleConstants;
import org.company.nianglin.dto.ReviewCreateDTO;
import org.company.nianglin.dto.ReviewRulingDTO;
import org.company.nianglin.entity.CompanionOrder;
import org.company.nianglin.entity.OrderReview;
import org.company.nianglin.exception.BusinessException;
import org.company.nianglin.mapper.CompanionScoreSnapshotMapper;
import org.company.nianglin.mapper.OrderReviewMapper;
import org.company.nianglin.mapper.ReviewReadMapper;
import org.company.nianglin.security.LoginUser;
import org.company.nianglin.service.impl.ReviewServiceImpl;
import org.company.nianglin.service.support.OperLogRecorder;
import org.company.nianglin.service.support.UserNameResolver;
import org.company.nianglin.support.MybatisLambdaCache;
import org.company.nianglin.vo.ReviewRulingResultVO;
import org.company.nianglin.util.MessageTemplateUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 评价「可重评」契约单测（closure-2026-11 · P1-2）。
 *
 * <p>本单盯的是「管理员把一条恶意差评裁定为无效之后，这一单还能不能重新评价」：
 * 旧实现把唯一键 {@code (order_id)} 当成「一单一评」的硬约束，裁定无效之后
 * 订单停在终态 REVIEWED、评价又提交不了，于是这一单的真实效果是「等于没人评过」，
 * 而系统两边都记成「已评价」。</p>
 *
 * @author 银龄伴诊团队
 * @since closure-2026-11
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("评价可重评：裁定无效后的正向出口")
class ReviewResequenceTest {

    private static final Long ORDER_ID = 1033L;
    private static final Long REVIEW_ID = 30003L;
    private static final Long FAMILY_ID = 103L;
    private static final Long COMPANION_ID = 309L;

    @Mock
    private OrderReviewMapper reviewMapper;
    @Mock
    private ReviewReadMapper reviewReadMapper;
    @Mock
    private CompanionScoreSnapshotMapper scoreSnapshotMapper;
    @Mock
    private OrderService orderService;
    @Mock
    private UserNameResolver userNameResolver;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private MessageService messageService;
    @Mock
    private OperLogRecorder operLogRecorder;

    private ReviewServiceImpl service;

    @BeforeEach
    void setUp() {
        MybatisLambdaCache.warmUp();
        service = new ReviewServiceImpl(reviewMapper, reviewReadMapper, scoreSnapshotMapper,
                orderService, userNameResolver, redisTemplate, new ObjectMapper(), messageService,
                operLogRecorder);
        loginAs(RoleConstants.FAMILY, FAMILY_ID);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    /* ================================================================== */

    @Test
    @DisplayName("重评 · 旧评价被裁定无效后，家属可再次评价，且 reviewSeq = 2")
    void rereviewShouldPersistWithSeqTwo() {
        // 订单停在终态 REVIEWED（终态不回退，符合全局状态机原则）
        given(orderService.requireInvolved(ORDER_ID)).willReturn(order(OrderStatus.REVIEWED));
        // 「有效评价数」为 0：旧的那条已被裁定无效
        given(reviewMapper.selectCount(any())).willReturn(0L);
        // 但它仍然留在库里（审计证据），seq = 1
        given(reviewMapper.selectOne(any())).willReturn(oldInvalidReview());
        given(reviewMapper.insert(any(OrderReview.class))).willReturn(1);

        service.create(reviewDto(5, "上一次评价与事实不符，这次重评才是真实体验"));

        ArgumentCaptor<OrderReview> cap = ArgumentCaptor.forClass(OrderReview.class);
        verify(reviewMapper).insert(cap.capture());
        assertEquals(2, cap.getValue().getReviewSeq(),
                "重评记录的 reviewSeq 必须是 2（首次评价为 1）");
        assertEquals(1, cap.getValue().getIsValid(), "重评是有效评价");
    }

    @Test
    @DisplayName("重评 · 已经用掉重评名额（seq=2）后再提交 → 6002（重评只有一次）")
    void thirdReviewShouldBeRejected() {
        given(orderService.requireInvolved(ORDER_ID)).willReturn(order(OrderStatus.REVIEWED));
        given(reviewMapper.selectCount(any())).willReturn(0L);
        OrderReview seqTwo = oldInvalidReview();
        seqTwo.setReviewSeq(2);
        given(reviewMapper.selectOne(any())).willReturn(seqTwo);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.create(reviewDto(5, "还想再评一次")));
        assertEquals(ResultCode.ORDER_ALREADY_REVIEWED.getCode(), ex.getCode());
        verify(reviewMapper, never()).insert(any(OrderReview.class));
    }

    @Test
    @DisplayName("裁定无效 · 站内信必须告诉家属「可以重新评价本单」")
    void rulingShouldTellFamilyCanRereview() {
        loginAs(RoleConstants.ADMIN, 1L);
        given(reviewMapper.selectById(REVIEW_ID)).willReturn(validReview());
        given(reviewMapper.update(any(), any())).willReturn(1);
        // 该订单只有 seq=1 一条评价 → 还有一次重评机会
        given(reviewMapper.selectOne(any())).willReturn(oldInvalidReview());

        service.reviewValidity(REVIEW_ID, rulingDto(false,
                "打卡记录与描述明显不符，证据充分，裁定为无效评价"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> cap = ArgumentCaptor.forClass(Map.class);
        verify(messageService).send(eq(FAMILY_ID), eq(MessageType.REVIEW_INVALIDATED),
                eq(ORDER_ID), cap.capture());
        Map<String, Object> params = cap.getValue();
        assertEquals("FAMILY", params.get("audience"),
                "给家属的通知必须带 audience=FAMILY，陪诊员那条不能带（否则文案会串台）");
        String content = MessageTemplateUtil.content(MessageType.REVIEW_INVALIDATED, params);
        assertEquals(true, content.contains("重新评价"),
                "家属必须被告知可以重新评价，否则「正向出口」在用户侧根本不存在");
    }

    @Test
    @DisplayName("裁定无效 · 响应要带 canReReview=true，前端据此提示家属可重评")
    void rulingShouldExposeCanRereview() {
        loginAs(RoleConstants.ADMIN, 1L);
        given(reviewMapper.selectById(REVIEW_ID)).willReturn(validReview());
        given(reviewMapper.update(any(), any())).willReturn(1);
        given(reviewMapper.selectOne(any())).willReturn(oldInvalidReview());

        ReviewRulingResultVO vo = service.reviewValidity(REVIEW_ID, rulingDto(false,
                "打卡记录与描述明显不符，证据充分，裁定为无效评价"));

        assertEquals(true, vo.getCanReReview(),
                "该订单只用过一次评价名额（seq=1），家属还能重评");
    }

    @Test
    @DisplayName("裁定无效 · 重评名额已用完（seq=2）→ canReReview=false")
    void rulingShouldExposeNoRereviewWhenQuotaUsed() {
        loginAs(RoleConstants.ADMIN, 1L);
        given(reviewMapper.selectById(REVIEW_ID)).willReturn(validReview());
        given(reviewMapper.update(any(), any())).willReturn(1);
        OrderReview seqTwo = oldInvalidReview();
        seqTwo.setReviewSeq(2);
        given(reviewMapper.selectOne(any())).willReturn(seqTwo);

        ReviewRulingResultVO vo = service.reviewValidity(REVIEW_ID, rulingDto(false,
                "打卡记录与描述明显不符，证据充分，裁定为无效评价"));

        assertEquals(false, vo.getCanReReview(),
                "重评只有一次机会，用完后不能再提示家属重评");
    }

    @Test
    @DisplayName("裁定无效 · 旧评价记录保留（审计可追溯），不做物理删除")
    void rulingShouldKeepOldReviewRecord() {
        loginAs(RoleConstants.ADMIN, 1L);
        given(reviewMapper.selectById(REVIEW_ID)).willReturn(validReview());
        given(reviewMapper.update(any(), any())).willReturn(1);
        given(reviewMapper.selectOne(any())).willReturn(oldInvalidReview());

        service.reviewValidity(REVIEW_ID, rulingDto(false,
                "打卡记录与描述明显不符，证据充分，裁定为无效评价"));

        // 只改 is_valid，不删行：删了就查不到「当初那条差评长什么样」，
        // 申诉与仲裁的证据链也就断了
        verify(reviewMapper, never()).deleteById(any(Long.class));
        verify(reviewMapper, never()).delete(any());
    }

    /* ================================================================== */

    private CompanionOrder order(OrderStatus status) {
        CompanionOrder order = new CompanionOrder();
        order.setId(ORDER_ID);
        order.setOrderNo("NL20260822000033");
        order.setFamilyId(FAMILY_ID);
        order.setElderId(403L);
        order.setCompanionId(COMPANION_ID);
        order.setStatus(status.name());
        return order;
    }

    private OrderReview validReview() {
        OrderReview review = oldInvalidReview();
        review.setIsValid(1);
        review.setReviewSeq(1);
        return review;
    }

    private ReviewRulingDTO rulingDto(boolean isValid, String reason) {
        ReviewRulingDTO dto = new ReviewRulingDTO();
        dto.setIsValid(isValid);
        dto.setReason(reason);
        return dto;
    }

    private OrderReview oldInvalidReview() {
        OrderReview review = new OrderReview();
        review.setId(30003L);
        review.setOrderId(ORDER_ID);
        review.setOrderNo("NL20260822000033");
        review.setFamilyId(FAMILY_ID);
        review.setCompanionId(COMPANION_ID);
        review.setScore(1);
        review.setIsValid(0);
        review.setReviewSeq(1);
        return review;
    }

    private ReviewCreateDTO reviewDto(int score, String content) {
        ReviewCreateDTO dto = new ReviewCreateDTO();
        dto.setOrderId(ORDER_ID);
        dto.setScore(score);
        dto.setContent(content);
        return dto;
    }

    private void loginAs(String role, Long userId) {
        LoginUser loginUser = new LoginUser(userId, "tester", role, 0, "jti",
                System.currentTimeMillis() + 60000);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, loginUser.authorities()));
    }
}

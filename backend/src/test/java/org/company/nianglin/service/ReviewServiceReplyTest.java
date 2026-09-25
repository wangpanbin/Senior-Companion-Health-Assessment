package org.company.nianglin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.company.nianglin.common.ResultCode;
import org.company.nianglin.constant.MessageType;
import org.company.nianglin.constant.RoleConstants;
import org.company.nianglin.dto.ReviewReplyDTO;
import org.company.nianglin.entity.OrderReview;
import org.company.nianglin.exception.BusinessException;
import org.company.nianglin.mapper.CompanionProfileMapper;
import org.company.nianglin.mapper.OrderReviewMapper;
import org.company.nianglin.mapper.ReviewReadMapper;
import org.company.nianglin.security.LoginUser;
import org.company.nianglin.service.impl.ReviewServiceImpl;
import org.company.nianglin.service.support.OperLogRecorder;
import org.company.nianglin.service.support.UserNameResolver;
import org.company.nianglin.support.MybatisLambdaCache;
import org.company.nianglin.vo.ReviewReplyResultVO;
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

import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 陪诊员回复评价（E4 评价公信力闭环）的契约单测。
 *
 * <p>本类盯的是<b>六条易错点</b>，按出问题时的「惊讶程度」排序：</p>
 *
 * <ol>
 *   <li><b>并发穿透</b>：两个请求同时通过「companion_reply 为空」检查，
 *       必须有一个被乐观条件（{@code isNull(companion_reply)}）翻译成 6006，
 *       否则会出现两条回复同时存在的「魔幻」状态。用「update 影响行 = 0」来锁死。</li>
 *   <li><b>通知摘要必须 ≤ 30 字</b>：模板渲染时拼成「陪诊员回复了您…（N 字…）」，
 *       N > 30 会让超长摘要穿透到站内信，给站内信通知的合规红线留口子。</li>
 *   <li><b>归属而非角色</b>：注解 {@code @PreAuthorize('COMPANION')} 只能挡住非陪诊员，
 *       陪诊员 A 回复陪诊员 B 的评价必须由 Service 层比对 {@code review.companionId} 完成。</li>
 *   <li><b>已回复不能被覆盖</b>：「一评一回复」是产品决策（见 spec §四），
 *       任何让回复被二次写入的实现都是 bug。</li>
 *   <li><b>敏感词必须在写之前命中</b>：内容先过 {@link org.company.nianglin.util.SensitiveWordUtil}
 *       再落库，与既有 {@code create} 顺序一致 —— 不能图省事让敏感词先入库再清理。</li>
 *   <li><b>不触发评分重算</b>：回复不参与聚合（spec §2.1），
 *       调用 {@code refreshCompanionScore} 反而会让缓存被无意义地清一次。</li>
 * </ol>
 *
 * @author 银龄伴诊团队
 * @since E4
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("回复评价：乐观条件 / 摘要长度 / 归属优先 / 不重算评分")
class ReviewServiceReplyTest {

    private static final Long REVIEW_ID = 2001L;
    private static final Long ORDER_ID = 1031L;
    private static final Long FAMILY_ID = 101L;
    private static final Long COMPANION_ID = 307L;
    private static final Long OTHER_COMPANION_ID = 308L;

    @Mock
    private OrderReviewMapper reviewMapper;

    @Mock
    private ReviewReadMapper reviewReadMapper;

    @Mock
    private CompanionProfileMapper companionProfileMapper;

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
        service = new ReviewServiceImpl(reviewMapper, reviewReadMapper, companionProfileMapper,
                orderService, userNameResolver, redisTemplate, new ObjectMapper(), messageService,
                operLogRecorder);
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
    @DisplayName("回复评价 · 成功：落库 + 通知家属 + 不刷评分")
    void replyShouldPersistAndNotifyAndSkipScoreRefresh() {
        OrderReview review = existingReview(null, null);
        given(reviewMapper.selectById(REVIEW_ID)).willReturn(review);
        given(reviewMapper.update(any(), any())).willReturn(1);

        ReviewReplyResultVO vo = service.reply(REVIEW_ID, replyDto("当日 08:20 已到院打卡，迟到或因老人下楼较慢。"));

        assertEquals(REVIEW_ID, vo.getReviewId());
        assertNotNull(vo.getReplyTime());

        // 通知参数断言
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> paramCaptor = ArgumentCaptor.forClass(Map.class);
        verify(messageService).send(eq(FAMILY_ID), eq(MessageType.REVIEW_REPLIED),
                eq(ORDER_ID), paramCaptor.capture());
        Map<String, Object> params = paramCaptor.getValue();
        assertEquals("NL20260820000031", params.get("orderNo"));
        String digest = (String) params.get("replyDigest");
        assertNotNull(digest);
        // 30 字内直接用原文，超过则截断 + 省略号。原文 23 字未触发截断
        assertEquals(true, digest.length() <= 31,
                "摘要必须 ≤ 30 字，避免超长内容绕过脱敏穿透到站内信");

        // 关键：回复不参与聚合，不能调 refreshCompanionScore
        verify(reviewReadMapper, never()).selectAverageScore(any());
        verify(companionProfileMapper, never()).update(any(), any());
    }

    /* ================================================================== */
    /* 2 · 五条失败路径                                                     */
    /* ================================================================== */

    @Test
    @DisplayName("回复评价 · 评价不存在 → 6005")
    void replyShouldRejectMissingReview() {
        given(reviewMapper.selectById(REVIEW_ID)).willReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.reply(REVIEW_ID, replyDto("当日 08:20 已到院打卡")));
        assertEquals(ResultCode.REVIEW_NOT_FOUND.getCode(), ex.getCode());
        verify(reviewMapper, never()).update(any(), any());
        verify(messageService, never()).send(any(), any(), any(), any());
    }

    @Test
    @DisplayName("回复评价 · 陪诊员 A 回复陪诊员 B 的评价 → 3004（归属优先于角色）")
    void replyShouldRejectOtherCompanion() {
        loginAs(RoleConstants.COMPANION, OTHER_COMPANION_ID);
        OrderReview review = existingReview(null, null); // companionId = COMPANION_ID, 当前登录 = OTHER_COMPANION_ID
        given(reviewMapper.selectById(REVIEW_ID)).willReturn(review);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.reply(REVIEW_ID, replyDto("当日 08:20 已到院打卡")));
        assertEquals(ResultCode.ORDER_NO_PERMISSION.getCode(), ex.getCode());
        verify(reviewMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("回复评价 · 已回复过 → 6006（先查后写的双防线）")
    void replyShouldRejectAlreadyReplied() {
        OrderReview review = existingReview("上次已回复", LocalDateTime.now().minusDays(1));
        given(reviewMapper.selectById(REVIEW_ID)).willReturn(review);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.reply(REVIEW_ID, replyDto("我又来了当日 08:20 已到院打卡")));
        assertEquals(ResultCode.REVIEW_ALREADY_REPLIED.getCode(), ex.getCode());
        verify(reviewMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("回复评价 · 命中敏感词 → 6003，且不落库、不通知")
    void replyShouldRejectSensitiveWord() {
        OrderReview review = existingReview(null, null);
        given(reviewMapper.selectById(REVIEW_ID)).willReturn(review);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.reply(REVIEW_ID, replyDto("你这个陪诊员就是骗子，态度极差")));
        assertEquals(ResultCode.CONTENT_SENSITIVE.getCode(), ex.getCode());
        verify(reviewMapper, never()).update(any(), any());
        verify(messageService, never()).send(any(), any(), any(), any());
    }

    @Test
    @DisplayName("回复评价 · 并发穿透：第二个请求拿到 update 影响行 = 0 → 6006")
    void replyShouldRejectConcurrentDoubleReply() {
        OrderReview review = existingReview(null, null);
        given(reviewMapper.selectById(REVIEW_ID)).willReturn(review);
        // update 返回 0：说明在我做 isNull 检查与 update 之间，对方先提交了
        given(reviewMapper.update(any(), any())).willReturn(0);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.reply(REVIEW_ID, replyDto("当日 08:20 已到院打卡，抢答尝试")));
        assertEquals(ResultCode.REVIEW_ALREADY_REPLIED.getCode(), ex.getCode());
        verify(messageService, never()).send(any(), any(), any(), any());
    }

    /* ================================================================== */
    /* 内部                                                                 */
    /* ================================================================== */

    private OrderReview existingReview(String existingReply, LocalDateTime existingReplyTime) {
        OrderReview review = new OrderReview();
        review.setId(REVIEW_ID);
        review.setOrderId(ORDER_ID);
        review.setOrderNo("NL20260820000031");
        review.setFamilyId(FAMILY_ID);
        review.setElderId(401L);
        review.setCompanionId(COMPANION_ID);
        review.setCompanionReply(existingReply);
        review.setReplyTime(existingReplyTime);
        review.setScore(5);
        review.setIsAnonymous(0);
        review.setIsValid(1);
        return review;
    }

    private ReviewReplyDTO replyDto(String content) {
        ReviewReplyDTO dto = new ReviewReplyDTO();
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
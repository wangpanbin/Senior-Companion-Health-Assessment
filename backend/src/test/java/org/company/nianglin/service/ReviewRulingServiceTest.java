package org.company.nianglin.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.company.nianglin.common.ResultCode;
import org.company.nianglin.constant.MessageType;
import org.company.nianglin.constant.OperTargetType;
import org.company.nianglin.constant.OperType;
import org.company.nianglin.constant.RoleConstants;
import org.company.nianglin.dto.ReviewRulingDTO;
import org.company.nianglin.entity.AdminOperLog;
import org.company.nianglin.entity.OrderReview;
import org.company.nianglin.exception.BusinessException;
import org.company.nianglin.mapper.AdminOperLogMapper;
import org.company.nianglin.mapper.AdminReadMapper;
import org.company.nianglin.mapper.CompanionAuditRecordMapper;
import org.company.nianglin.mapper.CompanionOrderMapper;
import org.company.nianglin.mapper.CompanionProfileMapper;
import org.company.nianglin.mapper.ComplaintMapper;
import org.company.nianglin.mapper.OrderReadMapper;
import org.company.nianglin.mapper.OrderReviewMapper;
import org.company.nianglin.mapper.SysUserMapper;
import org.company.nianglin.security.LoginUser;
import org.company.nianglin.security.SecurityProperties;
import org.company.nianglin.security.TokenStore;
import org.company.nianglin.service.impl.AdminServiceImpl;
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
import org.springframework.security.crypto.password.PasswordEncoder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 管理员评价有效性裁定（E4 评价公信力闭环）的契约单测。
 *
 * <p>本类盯的是<b>六条容易漏掉的硬约束</b>，按出问题时的「公信力代价」排序：</p>
 *
 * <ol>
 *   <li><b>三件套必须同一事务</b>：置位 + 重算评分 + 写日志。任何一件失败整体回滚，
 *       否则会出现「评价已无效但评分没变」或「评分已变但日志显示未操作」的不一致。</li>
 *   <li><b>单向裁定</b>：本接口只接受 {@code isValid=false}，「恢复有效」不在本期范围
 *       （PRD §FR-03 规则 2）。把它做成可选会让「撤销已通知双方的裁定」成本极低。</li>
 *   <li><b>乐观条件</b>：{@code update} 影响行 = 0 翻译成 409。挡住「在我做 isValid=1 检查
 *       与 update 之间，对方（管理员）已经裁定过」的并发场景。</li>
 *   <li><b>通知双收件人</b>：评价家属 + 陪诊员各发一条，少发一条都会让另一方「不知道这件事」。</li>
 *   <li><b>通知摘要 ≤ 30 字</b>：与回复评价同款脱敏红线（避免站内信成为绕过脱敏的正文泄露出口）。</li>
 *   <li><b>已裁定过 → 409</b>：评价既不能改回有效，也不能重复改无效。</li>
 * </ol>
 *
 * @author 银龄伴诊团队
 * @since E4
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("评价有效性裁定：三件套事务 / 单向裁定 / 乐观条件 / 双收件人通知")
class ReviewRulingServiceTest {

    private static final Long REVIEW_ID = 30001L;
    private static final Long ORDER_ID = 1031L;
    private static final Long FAMILY_ID = 101L;
    private static final Long COMPANION_ID = 307L;
    private static final Long ADMIN_ID = 1L;

    @Mock
    private CompanionAuditRecordMapper auditRecordMapper;
    @Mock
    private CompanionProfileMapper companionProfileMapper;
    @Mock
    private SysUserMapper sysUserMapper;
    @Mock
    private ComplaintMapper complaintMapper;
    @Mock
    private CompanionOrderMapper orderMapper;
    @Mock
    private AdminOperLogMapper operLogMapper;
    @Mock
    private AdminReadMapper adminReadMapper;
    @Mock
    private OrderReadMapper orderReadMapper;
    @Mock
    private MessageService messageService;
    @Mock
    private OrderService orderService;
    @Mock
    private UserNameResolver userNameResolver;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private SecurityProperties securityProperties;
    @Mock
    private TokenStore tokenStore;
    @Mock
    private OrderReviewMapper orderReviewMapper;
    @Mock
    private ReviewService reviewService;

    private AdminServiceImpl service;

    @BeforeEach
    void setUp() {
        MybatisLambdaCache.warmUp();
        service = new AdminServiceImpl(auditRecordMapper, companionProfileMapper, sysUserMapper,
                complaintMapper, orderMapper, operLogMapper, adminReadMapper, orderReadMapper,
                messageService, orderService, userNameResolver, passwordEncoder, securityProperties,
                tokenStore, new ObjectMapper(), orderReviewMapper, reviewService);
        loginAs(RoleConstants.ADMIN, ADMIN_ID);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    /* ================================================================== */
    /* 1 · 正常路径                                                         */
    /* ================================================================== */

    @Test
    @DisplayName("裁定 · 成功：is_valid=0 + 重算评分 + 写日志 + 双收件人通知，同事务")
    void rulingShouldInvalidateRefreshScoreAndNotify() {
        OrderReview review = validReview();
        given(orderReviewMapper.selectById(REVIEW_ID)).willReturn(review);
        given(orderReviewMapper.update(any(), any())).willReturn(1);

        service.reviewValidity(REVIEW_ID, rulingDto(false,
                "家属描述与打卡记录明显不符，证据充分，裁定为无效评价"));

        // ① is_valid 置 0（乐观条件 eq(isValid, 1)）
        // MyBatis-Plus 的 LambdaUpdateWrapper.getSqlSegment() 只返回 WHERE 子句
        // （SET 子句在 .set() 调用里另外维护）；这里只校 WHERE 即可
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<OrderReview>> updateCap =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper.class);
        verify(orderReviewMapper).update(eq(null), updateCap.capture());
        String sql = updateCap.getValue().getSqlSegment();
        // MyBatis-Plus 渲染的 SQL 用 #{ew.paramNameValuePairs.MPGENVALn} 而非裸 ? —— 比较时按列名匹配
        assertEquals(true, sql.contains("id =") && sql.contains("is_valid ="),
                "乐观条件 WHERE 必须同时包含 id 与 is_valid，挡住并发穿透");
        String sqlSet = updateCap.getValue().getSqlSet();
        assertEquals(true, sqlSet.contains("is_valid="),
                "SET 子句必须包含 is_valid=（置位，无空格）");

        // ② 评分重算
        verify(reviewService).refreshCompanionScore(COMPANION_ID);

        // ③ admin_oper_log 写入（带 OperType.REVIEW_RULING + OperTargetType.REVIEW + reason）
        ArgumentCaptor<AdminOperLog> logCap = ArgumentCaptor.forClass(AdminOperLog.class);
        verify(operLogMapper).insert(logCap.capture());
        AdminOperLog log = logCap.getValue();
        assertEquals(OperType.REVIEW_RULING.name(), log.getOperType());
        assertEquals(OperTargetType.REVIEW.name(), log.getTargetType());
        assertEquals(REVIEW_ID, log.getTargetId());
        assertEquals("IS_VALID:1", log.getBeforeStatus());
        assertEquals("IS_VALID:0", log.getAfterStatus());
        assertEquals("家属描述与打卡记录明显不符，证据充分，裁定为无效评价", log.getRemark());

        // ④ 双收件人通知（家属 + 陪诊员，bizId=orderId）
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> paramCap = ArgumentCaptor.forClass(Map.class);
        verify(messageService, times(2)).send(any(), eq(MessageType.REVIEW_INVALIDATED),
                eq(ORDER_ID), paramCap.capture());
        // 两次通知参数一致，摘要 ≤ 30 字
        Map<String, Object> params = paramCap.getValue();
        String digest = (String) params.get("reasonDigest");
        assertEquals(true, digest.length() <= 31,
                "摘要必须 ≤ 30 字，避免超长理由穿透到站内信");
        // 收件人两条都验证一下
        verify(messageService).send(eq(FAMILY_ID), eq(MessageType.REVIEW_INVALIDATED),
                eq(ORDER_ID), any());
        verify(messageService).send(eq(COMPANION_ID), eq(MessageType.REVIEW_INVALIDATED),
                eq(ORDER_ID), any());
    }

    /* ================================================================== */
    /* 2 · 五条失败路径                                                     */
    /* ================================================================== */

    @Test
    @DisplayName("裁定 · 评价不存在 → 6005")
    void rulingShouldRejectMissingReview() {
        given(orderReviewMapper.selectById(REVIEW_ID)).willReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.reviewValidity(REVIEW_ID, rulingDto(false, "理由足够长足够长足够长足够长")));
        assertEquals(ResultCode.REVIEW_NOT_FOUND.getCode(), ex.getCode());
        verify(orderReviewMapper, never()).update(any(), any());
        verify(reviewService, never()).refreshCompanionScore(any());
        verify(operLogMapper, never()).insert(any(AdminOperLog.class));
        verify(messageService, never()).send(any(), any(), any(), any());
    }

    @Test
    @DisplayName("裁定 · isValid=true 被拒 → 400（单向裁定，恢复有效不在本期范围）")
    void rulingShouldRejectRestoreValid() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.reviewValidity(REVIEW_ID, rulingDto(true, "理由足够长足够长足够长足够长")));
        assertEquals(ResultCode.PARAM_ERROR.getCode(), ex.getCode());
        verify(orderReviewMapper, never()).selectById(any());
    }

    @Test
    @DisplayName("裁定 · 理由 < 10 字符 → 400（必须够长，避免管理员随手写个「好」字就生效）")
    void rulingShouldRejectShortReason() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.reviewValidity(REVIEW_ID, rulingDto(false, "太短了")));
        assertEquals(ResultCode.PARAM_ERROR.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("裁定 · 已被裁定无效（is_valid=0）→ 409，幂等拒绝")
    void rulingShouldRejectAlreadyInvalid() {
        OrderReview invalidated = validReview();
        invalidated.setIsValid(0);
        given(orderReviewMapper.selectById(REVIEW_ID)).willReturn(invalidated);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.reviewValidity(REVIEW_ID, rulingDto(false, "理由足够长足够长足够长足够长")));
        assertEquals(ResultCode.CONFLICT.getCode(), ex.getCode());
        verify(orderReviewMapper, never()).update(any(), any());
        verify(messageService, never()).send(any(), any(), any(), any());
    }

    @Test
    @DisplayName("裁定 · 并发穿透：update 影响行 = 0（在我检查与 update 之间被对方抢锁）→ 409")
    void rulingShouldRejectConcurrentDoubleRuling() {
        OrderReview review = validReview();
        given(orderReviewMapper.selectById(REVIEW_ID)).willReturn(review);
        given(orderReviewMapper.update(any(), any())).willReturn(0);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.reviewValidity(REVIEW_ID, rulingDto(false, "理由足够长足够长足够长足够长")));
        assertEquals(ResultCode.CONFLICT.getCode(), ex.getCode());
        // 关键：并发穿透时不应触发重算与通知 —— 否则会出现「数据库没改但评分已重算 + 用户收到通知」的鬼故事
        verify(reviewService, never()).refreshCompanionScore(any());
        verify(operLogMapper, never()).insert(any(AdminOperLog.class));
        verify(messageService, never()).send(any(), any(), any(), any());
    }

    /* ================================================================== */

    private OrderReview validReview() {
        OrderReview review = new OrderReview();
        review.setId(REVIEW_ID);
        review.setOrderId(ORDER_ID);
        review.setOrderNo("NL20260820000031");
        review.setFamilyId(FAMILY_ID);
        review.setCompanionId(COMPANION_ID);
        review.setScore(1);
        review.setIsAnonymous(0);
        review.setIsValid(1);
        review.setCompanionReply(null);
        review.setReplyTime(null);
        review.setCreateTime(LocalDateTime.now().minusDays(3));
        return review;
    }

    private ReviewRulingDTO rulingDto(boolean isValid, String reason) {
        ReviewRulingDTO dto = new ReviewRulingDTO();
        dto.setIsValid(isValid);
        dto.setReason(reason);
        return dto;
    }

    private void loginAs(String role, Long userId) {
        LoginUser loginUser = new LoginUser(userId, "tester", role, 0, "jti",
                System.currentTimeMillis() + 60000);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, loginUser.authorities()));
    }
}
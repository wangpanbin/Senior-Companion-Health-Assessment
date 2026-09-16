package org.company.nianglin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.company.nianglin.common.ResultCode;
import org.company.nianglin.constant.RoleConstants;
import org.company.nianglin.dto.MessageQuery;
import org.company.nianglin.entity.InternalMessage;
import org.company.nianglin.exception.BusinessException;
import org.company.nianglin.mapper.InternalMessageMapper;
import org.company.nianglin.mapper.MessageReadMapper;
import org.company.nianglin.security.LoginUser;
import org.company.nianglin.service.impl.MessageServiceImpl;
import org.company.nianglin.support.MybatisLambdaCache;
import org.company.nianglin.vo.MessageReadResultVO;
import org.company.nianglin.vo.UnreadCountVO;
import org.company.nianglin.websocket.MessageSseHub;
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

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 站内信服务单测（M8）。
 *
 * <p>M8 的控制器上一个 {@code @PreAuthorize} 都没有，看得到什么完全由本类决定。
 * 所以这里的用例全部围绕「<b>收件人条件有没有被写进 SQL</b>」这一点：</p>
 *
 * <ol>
 *   <li>{@code markRead} / {@code delete} 的更新语句必须同时带
 *       {@code receiver_id} 与 {@code is_read = 0} ——
 *       前者是越权防线，后者是并发去重的乐观锁。少任何一个都不会报错，
 *       只会静默地改掉别人的数据或重复扣减未读数。</li>
 *   <li>{@code 7001} 与 {@code 7002} 必须分开：「这条不存在」与「这条不是你的」
 *       在排查越权问题时是完全不同的两条线索。</li>
 *   <li>已读的消息重复标记要幂等：返回 {@code affected = 0}，
 *       而不是再写一次库、也不是报错。</li>
 *   <li>未读数<b>数据库是唯一真源</b>，Redis 只是加速。
 *       用例刻意让缓存值与库里的值<b>不一致</b>，用来验证究竟谁说了算。</li>
 * </ol>
 *
 * @author 银龄伴诊团队
 * @since M8
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("站内信服务：收件人条件 / 幂等标记 / 缓存与真源")
class MessageServiceTest {

    private static final Long ME_ID = 301L;
    private static final Long MESSAGE_ID = 40001L;
    private static final Long OTHERS_MESSAGE_ID = 40002L;

    @Mock
    private InternalMessageMapper messageMapper;

    @Mock
    private MessageReadMapper messageReadMapper;

    @Mock
    private MessageSseHub sseHub;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOps;

    private MessageServiceImpl service;

    @BeforeEach
    void setUp() {
        MybatisLambdaCache.warmUp();
        service = new MessageServiceImpl(messageMapper, messageReadMapper, sseHub, redisTemplate);
        loginAs(RoleConstants.COMPANION, ME_ID);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    /* ================================================================== */
    /* 1 · 归属判定：7001 与 7002 必须分开                                  */
    /* ================================================================== */

    @Test
    @DisplayName("标记已读 · 消息不存在 → 7001，且不做任何写入")
    void markReadShouldReport7001WhenMissing() {
        given(messageMapper.selectById(MESSAGE_ID)).willReturn(null);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.markRead(MESSAGE_ID));

        assertEquals(ResultCode.MESSAGE_NOT_FOUND.getCode(), ex.getCode());
        verify(messageMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("标记已读 · 是别人的消息 → 7002（存在但不可见，线索与 7001 不同）")
    void markReadShouldReport7002ForOthersMessage() {
        given(messageMapper.selectById(OTHERS_MESSAGE_ID)).willReturn(message(OTHERS_MESSAGE_ID, ME_ID + 1, 0));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.markRead(OTHERS_MESSAGE_ID));

        assertEquals(ResultCode.MESSAGE_NO_PERMISSION.getCode(), ex.getCode());
        verify(messageMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("删除消息 · 是别人的消息 → 7002，且不写 receiver_deleted")
    void deleteShouldReport7002ForOthersMessage() {
        given(messageMapper.selectById(OTHERS_MESSAGE_ID)).willReturn(message(OTHERS_MESSAGE_ID, ME_ID + 1, 0));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.delete(OTHERS_MESSAGE_ID));

        assertEquals(ResultCode.MESSAGE_NO_PERMISSION.getCode(), ex.getCode());
        verify(messageMapper, never()).update(any(), any());
    }

    /* ================================================================== */
    /* 2 · 标记已读：幂等 + 条件更新                                         */
    /* ================================================================== */

    @Test
    @DisplayName("标记已读 · 本来就已读 → affected = 0 且不再写库（幂等）")
    void markReadShouldBeIdempotent() {
        given(messageMapper.selectById(MESSAGE_ID)).willReturn(message(MESSAGE_ID, ME_ID, 1));

        MessageReadResultVO result = service.markRead(MESSAGE_ID);

        assertEquals(0, result.getAffected(), "重复标记必须是 0 次影响，前端据此判断是否需要刷新");
        assertEquals(0L, result.getUnreadCount());
        verify(messageMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("标记已读 · 未读 → affected = 1，且 WHERE 必须同时带 receiver_id 与 is_read = 0")
    void markReadShouldGuardByReceiverAndUnreadFlag() {
        given(messageMapper.selectById(MESSAGE_ID)).willReturn(message(MESSAGE_ID, ME_ID, 0));
        given(messageMapper.update(any(), any())).willReturn(1);

        MessageReadResultVO result = service.markRead(MESSAGE_ID);

        assertEquals(1, result.getAffected());
        LambdaUpdateWrapper<InternalMessage> update = captureMessageUpdate();
        String setSql = update.getSqlSet();
        String whereSql = update.getSqlSegment();
        assertTrue(setSql.contains("is_read"), setSql);
        assertTrue(setSql.contains("read_time"), setSql);
        assertTrue(whereSql.contains("receiver_id"),
                "WHERE 少了 receiver_id，任何登录用户都能把别人的消息标成已读：" + whereSql);
        assertTrue(whereSql.contains("is_read"),
                "WHERE 少了 is_read = 0，并发标记会重复扣减未读数：" + whereSql);
    }

    @Test
    @DisplayName("标记已读 · 条件更新未命中时返回 affected = 0，而不是假装成功")
    void markReadShouldReflectZeroAffected() {
        given(messageMapper.selectById(MESSAGE_ID)).willReturn(message(MESSAGE_ID, ME_ID, 0));
        given(messageMapper.update(any(), any())).willReturn(0);

        assertEquals(0, service.markRead(MESSAGE_ID).getAffected());
    }

    /* ================================================================== */
    /* 3 · 删除：只影响接收人自己的视图                                       */
    /* ================================================================== */

    @Test
    @DisplayName("删除消息 · 只置 receiver_deleted，不动物理行也不碰 is_read")
    void deleteShouldOnlyHideFromReceiverView() {
        given(messageMapper.selectById(MESSAGE_ID)).willReturn(message(MESSAGE_ID, ME_ID, 0));

        service.delete(MESSAGE_ID);

        LambdaUpdateWrapper<InternalMessage> update = captureMessageUpdate();
        String setSql = update.getSqlSet();
        assertTrue(setSql.contains("receiver_deleted"), setSql);
        assertFalse(setSql.contains("is_read"),
                "删除只是「我不想再看到它」，顺手标已读会污染未读统计：" + setSql);
        assertTrue(update.getSqlSegment().contains("receiver_id"), update.getSqlSegment());
        // 服务端仍保留「这条消息发出去过」的事实，日志审计要用
        verify(messageMapper, never()).deleteById(any(Long.class));
    }

    /* ================================================================== */
    /* 4 · 未读数：数据库是唯一真源                                           */
    /* ================================================================== */

    @Test
    @DisplayName("未读数 · 缓存命中时以缓存为准（即使分组明细来自数据库）")
    void unreadCountShouldPreferCacheOnHit() {
        given(redisTemplate.opsForValue()).willReturn(valueOps);
        given(valueOps.get(anyString())).willReturn("5");
        // 库里的分组明细合计只有 2 —— 如果总数被它影响，说明缓存白读了
        given(messageReadMapper.countUnreadGroupByType(ME_ID))
                .willReturn(List.of(Map.of("type", "ORDER_ACCEPTED", "cnt", 2L)));

        UnreadCountVO vo = service.unreadCount();

        assertEquals(5L, vo.getTotal(), "缓存命中时总数应直接取缓存");
        assertEquals(2L, vo.getByType().get("ORDER_ACCEPTED"), "分组明细每次都查库，不维护第二份缓存");
    }

    @Test
    @DisplayName("未读数 · 缓存缺失时从库聚合，并把结果写回缓存")
    void unreadCountShouldAggregateAndBackfillOnMiss() {
        given(redisTemplate.opsForValue()).willReturn(valueOps);
        given(valueOps.get(anyString())).willReturn(null);
        given(messageReadMapper.countUnreadGroupByType(ME_ID)).willReturn(List.of(
                Map.of("type", "ORDER_ACCEPTED", "cnt", 2L),
                Map.of("type", "MEDICATION_REMIND", "cnt", 3L)));

        UnreadCountVO vo = service.unreadCount();

        assertEquals(5L, vo.getTotal());
        assertEquals(3L, vo.getByType().get("MEDICATION_REMIND"));
        verify(valueOps).set(anyString(), anyString(), any(java.time.Duration.class));
    }

    @Test
    @DisplayName("未读数 · 计数为 0 的类型不进明细（返回 0 的键会让前端多画一个空分类）")
    void unreadCountShouldDropZeroCountRows() {
        given(redisTemplate.opsForValue()).willReturn(valueOps);
        given(valueOps.get(anyString())).willReturn(null);
        given(messageReadMapper.countUnreadGroupByType(ME_ID)).willReturn(List.of(
                Map.of("type", "ORDER_ACCEPTED", "cnt", 1L),
                Map.of("type", "COMPLAINT_HANDLED", "cnt", 0L)));

        UnreadCountVO vo = service.unreadCount();

        assertEquals(1L, vo.getTotal());
        assertFalse(vo.getByType().containsKey("COMPLAINT_HANDLED"));
    }

    /* ================================================================== */
    /* 5 · 列表与批量已读：收件人条件必须写进 SQL                             */
    /* ================================================================== */

    @Test
    @DisplayName("消息列表 · 查询条件里必须带 receiver_id（这一行删掉就变成全站消息）")
    void listShouldFilterByReceiverId() {
        Page<InternalMessage> page = new Page<>();
        page.setRecords(List.of(message(MESSAGE_ID, ME_ID, 0)));
        given(messageMapper.selectPage(any(), any())).willReturn(page);

        service.list(new MessageQuery());

        LambdaQueryWrapper<InternalMessage> wrapper = captureMessageQuery();
        String sql = wrapper.getSqlSegment();
        assertTrue(sql.contains("receiver_id"), "列表没有按收件人收窄，就是全站消息泄露：" + sql);
        assertTrue(sql.contains("receiver_deleted"), "已被接收人删除的消息不该再出现在列表里：" + sql);
    }

    @Test
    @DisplayName("全部已读 · 更新条件同样必须带 receiver_id（管理员在这里没有任何特权）")
    void markAllReadShouldFilterByReceiverId() {
        given(messageMapper.update(any(), any())).willReturn(3);

        service.markAllRead(null);

        LambdaUpdateWrapper<InternalMessage> update = captureMessageUpdate();
        String whereSql = update.getSqlSegment();
        assertTrue(whereSql.contains("receiver_id"),
                "批量已读少了 receiver_id，一次点击就会清空全站红点：" + whereSql);
        assertTrue(whereSql.contains("is_read"), whereSql);
    }

    /* ================================================================== */

    private InternalMessage message(Long id, Long receiverId, Integer isRead) {
        InternalMessage message = new InternalMessage();
        message.setId(id);
        message.setReceiverId(receiverId);
        message.setSenderId(1L);
        message.setType("ORDER_ACCEPTED");
        message.setTitle("订单已接单");
        message.setContent("陪诊员李*已接下订单 NL20260819000002。");
        message.setIsRead(isRead);
        message.setReceiverDeleted(0);
        return message;
    }

    private void loginAs(String role, Long userId) {
        LoginUser loginUser = new LoginUser(userId, "tester", role, 0, "jti",
                System.currentTimeMillis() + 60000);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, loginUser.authorities()));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private LambdaUpdateWrapper<InternalMessage> captureMessageUpdate() {
        ArgumentCaptor<LambdaUpdateWrapper> captor = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(messageMapper).update(any(), captor.capture());
        return captor.getValue();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private LambdaQueryWrapper<InternalMessage> captureMessageQuery() {
        ArgumentCaptor<LambdaQueryWrapper> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(messageMapper).selectPage(any(), captor.capture());
        return captor.getValue();
    }
}

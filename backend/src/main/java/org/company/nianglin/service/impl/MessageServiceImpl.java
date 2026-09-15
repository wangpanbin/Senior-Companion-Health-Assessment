package org.company.nianglin.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.company.nianglin.common.PageResult;
import org.company.nianglin.common.ResultCode;
import org.company.nianglin.constant.MessageType;
import org.company.nianglin.constant.RedisKeyConstants;
import org.company.nianglin.dto.MessageQuery;
import org.company.nianglin.entity.InternalMessage;
import org.company.nianglin.exception.BusinessException;
import org.company.nianglin.mapper.InternalMessageMapper;
import org.company.nianglin.mapper.MessageReadMapper;
import org.company.nianglin.security.LoginUser;
import org.company.nianglin.security.SecurityUtils;
import org.company.nianglin.service.MessageService;
import org.company.nianglin.util.MessageTemplateUtil;
import org.company.nianglin.vo.MessageReadResultVO;
import org.company.nianglin.vo.MessageVO;
import org.company.nianglin.vo.UnreadCountVO;
import org.company.nianglin.websocket.MessageSseHub;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 站内信服务实现。
 *
 * <h3>归属校验为什么做两层</h3>
 *
 * <p>列表用 SQL 的 {@code receiver_id = 当前用户} 收窄，单条操作用
 * {@code selectById} 之后在 Service 里再比一次 {@code receiverId}。
 * 看起来冗余，但两层防的是不同的事：SQL 那层防「列表里混进别人的」，
 * Service 那层防「拿着别人的 messageId 直接调接口」——
 * 前者挡不住后者。文档 §1 的实现要点也是这么要求的（{@code 7002}）。</p>
 *
 * <h3>未读数缓存：只在「读操作」上失效，不在「写操作」上猜</h3>
 *
 * <p>文档建议用 Redis {@code INCR / DECR} 维护未读数。这里做了个收敛：
 * <b>新消息来时如果缓存已存在就 {@code INCR}，而任何「已读 / 删除」操作直接把键删掉</b>，
 * 下一次读未读数时从数据库重新聚合。</p>
 *
 * <p>不照搬 {@code DECR} 的原因是它很难保证正确：一条消息被标记已读、
 * 又被删除、又被重复标记已读，{@code DECR} 的调用次数与真实未读数之间
 * 只能靠人肉对齐；而一旦某个分支漏了一次 {@code DECR}，
 * 顶栏红点就会永远错误下去，且没有任何机制能自愈。
 * 用「写路径失效 + 读路径重算」时，最坏情况只是多查一次数据库。</p>
 *
 * <p>缓存 TTL 60 秒，即便某次忘记失效，也会在一分钟内自动收敛。</p>
 *
 * @author 银龄伴诊团队
 * @since M8
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MessageServiceImpl implements MessageService {

    /** 未读数缓存 TTL：即使某条失效路径被遗漏，最坏 60 秒后也会自愈 */
    private static final Duration UNREAD_CACHE_TTL = Duration.ofSeconds(60);

    private final InternalMessageMapper messageMapper;
    private final MessageReadMapper messageReadMapper;
    private final MessageSseHub sseHub;
    private final StringRedisTemplate redisTemplate;

    /* ================================================================== */
    /* 1. 消息列表                                                         */
    /* ================================================================== */

    @Override
    public PageResult<MessageVO> list(MessageQuery query) {
        LoginUser me = SecurityUtils.currentUser();

        LambdaQueryWrapper<InternalMessage> wrapper = Wrappers.<InternalMessage>lambdaQuery()
                // 这一行是「只能看自己的消息」的 SQL 层防线，删掉它列表就会变成全站消息
                .eq(InternalMessage::getReceiverId, me.userId())
                // 用户删掉的消息不该再出现在列表里（物理行保留，仅接收人视图隐藏）
                .eq(InternalMessage::getReceiverDeleted, 0);

        List<MessageType> types = MessageType.parseList(query.getType());
        if (!types.isEmpty()) {
            wrapper.in(InternalMessage::getType, types.stream().map(Enum::name).toList());
        }
        if (query.getIsRead() != null) {
            wrapper.eq(InternalMessage::getIsRead, query.getIsRead() ? 1 : 0);
        }
        applyDateRange(wrapper, query.getStartDate(), query.getEndDate());

        wrapper.orderByDesc(InternalMessage::getCreateTime).orderByDesc(InternalMessage::getId);

        Page<InternalMessage> page = messageMapper.selectPage(query.toMpPage(), wrapper);
        return PageResult.of(page, MessageVO::of);
    }

    /* ================================================================== */
    /* 2. 未读数                                                           */
    /* ================================================================== */

    @Override
    public UnreadCountVO unreadCount() {
        LoginUser me = SecurityUtils.currentUser();
        return loadUnreadCount(me.userId());
    }

    /* ================================================================== */
    /* 3. 标记单条已读                                                     */
    /* ================================================================== */

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MessageReadResultVO markRead(Long messageId) {
        LoginUser me = SecurityUtils.currentUser();
        InternalMessage message = requireMine(messageId, me);

        if (message.getIsRead() != null && message.getIsRead() == 1) {
            // 幂等：重复标记不报错，也不重复扣未读数。
            // 返回当前真实未读数而不是 0，前端可以无脑覆盖顶栏数字
            return MessageReadResultVO.of(0, currentUnreadTotal(me.userId()));
        }

        LocalDateTime now = LocalDateTime.now();
        // 条件更新 + is_read = 0：两个请求同时标记同一条时只有一个 affected = 1
        int rows = messageMapper.update(null, Wrappers.<InternalMessage>lambdaUpdate()
                .eq(InternalMessage::getId, messageId)
                .eq(InternalMessage::getReceiverId, me.userId())
                .eq(InternalMessage::getIsRead, 0)
                .set(InternalMessage::getIsRead, 1)
                .set(InternalMessage::getReadTime, now));

        invalidateUnreadCache(me.userId());
        long total = currentUnreadTotal(me.userId());
        log.debug("站内信标记已读 | messageId={} | userId={} | affected={} | unread={}",
                messageId, me.userId(), rows, total);
        return MessageReadResultVO.of(rows, total);
    }

    /* ================================================================== */
    /* 4. 全部已读                                                         */
    /* ================================================================== */

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MessageReadResultVO markAllRead(String type) {
        LoginUser me = SecurityUtils.currentUser();
        List<MessageType> types = MessageType.parseList(type);

        var update = Wrappers.<InternalMessage>lambdaUpdate()
                .eq(InternalMessage::getReceiverId, me.userId())
                .eq(InternalMessage::getReceiverDeleted, 0)
                .eq(InternalMessage::getIsRead, 0)
                .set(InternalMessage::getIsRead, 1)
                .set(InternalMessage::getReadTime, LocalDateTime.now());
        if (!types.isEmpty()) {
            update.in(InternalMessage::getType, types.stream().map(Enum::name).toList());
        }

        int rows = messageMapper.update(null, update);
        invalidateUnreadCache(me.userId());
        long total = currentUnreadTotal(me.userId());
        log.info("站内信全部已读 | userId={} | type={} | affected={} | 剩余未读={}",
                me.userId(), types.isEmpty() ? "ALL" : types, rows, total);
        return MessageReadResultVO.of(rows, total);
    }

    /* ================================================================== */
    /* 5. 删除消息                                                         */
    /* ================================================================== */

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long messageId) {
        LoginUser me = SecurityUtils.currentUser();
        requireMine(messageId, me);

        // 只置 receiver_deleted，不动 MyBatis-Plus 的 deleted：
        // 日志审计与「这条消息发出去过」的事实要保留，
        // 删除只是「我不想在自己的列表里再看到它」
        messageMapper.update(null, Wrappers.<InternalMessage>lambdaUpdate()
                .eq(InternalMessage::getId, messageId)
                .eq(InternalMessage::getReceiverId, me.userId())
                .set(InternalMessage::getReceiverDeleted, 1));

        invalidateUnreadCache(me.userId());
        log.info("站内信已删除 | messageId={} | userId={}", messageId, me.userId());
    }

    /* ================================================================== */
    /* 6. SSE 订阅                                                         */
    /* ================================================================== */

    @Override
    public SseEmitter subscribe() {
        LoginUser me = SecurityUtils.currentUser();
        return sseHub.subscribe(me.userId());
    }

    /* ================================================================== */
    /* 7. 发送（供 M5 / M6 / M7 / M9 调用）                                 */
    /* ================================================================== */

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long send(Long receiverId, MessageType type, Long bizId, Map<String, Object> params) {
        if (receiverId == null || type == null) {
            // 收件人不存在是业务上的正常情况（例如订单还没有陪诊员），
            // 不是错误：调用方不必为了「可能没有收件人」写一堆判空
            return null;
        }

        String title = MessageTemplateUtil.truncate(MessageTemplateUtil.title(type),
                MessageTemplateUtil.MAX_TITLE_LENGTH);
        String content = MessageTemplateUtil.truncate(MessageTemplateUtil.content(type, params),
                MessageTemplateUtil.MAX_CONTENT_LENGTH);

        InternalMessage row = new InternalMessage();
        row.setReceiverId(receiverId);
        row.setSenderId(null);
        row.setType(type.name());
        row.setTitle(title);
        row.setContent(content);
        row.setBizType(type.getBizType());
        row.setBizId(bizId);
        row.setLinkUrl(MessageTemplateUtil.linkUrl(type, bizId));
        row.setIsRead(0);
        row.setReceiverDeleted(0);
        messageMapper.insert(row);

        // 缓存存在才 INCR：不存在说明当前没有可信的缓存值，
        // 保持「不存在」比写一个可能错的数字更好 —— 读的时候会从库里算
        String key = RedisKeyConstants.messageUnread(receiverId);
        if (Boolean.TRUE.equals(redisTemplate.hasKey(key))) {
            redisTemplate.opsForValue().increment(key);
        }

        pushNewMessage(receiverId, row);
        log.info("站内信已发送 | messageId={} | receiverId={} | type={}", row.getId(), receiverId, type);
        return row.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int sendBatch(Collection<Long> receiverIds, MessageType type, Long bizId, Map<String, Object> params) {
        if (receiverIds == null || receiverIds.isEmpty() || type == null) {
            return 0;
        }
        int sent = 0;
        for (Long receiverId : receiverIds.stream().filter(java.util.Objects::nonNull).distinct().toList()) {
            if (send(receiverId, type, bizId, params) != null) {
                sent++;
            }
        }
        return sent;
    }

    /* ================================================================== */
    /* 内部工具：未读数                                                    */
    /* ================================================================== */

    /**
     * 读未读数：优先走缓存，缓存缺失时从库里聚合再写回。
     *
     * <p><b>数据库是唯一真源</b>，缓存只是加速。这条原则让「缓存被清空」
     * 与「缓存不一致」都退化成「慢一点」，而不是「红点数字错了」。</p>
     */
    private UnreadCountVO loadUnreadCount(Long userId) {
        String key = RedisKeyConstants.messageUnread(userId);
        Long cached = readCachedTotal(key);
        if (cached != null) {
            return UnreadCountVO.of(cached, loadUnreadByType(userId));
        }
        UnreadCountVO fresh = aggregateFromDatabase(userId);
        redisTemplate.opsForValue().set(key, String.valueOf(fresh.getTotal()), UNREAD_CACHE_TTL);
        return fresh;
    }

    /** 只取总数（用于标记已读后的返回），不查分组 */
    private long currentUnreadTotal(Long userId) {
        return aggregateFromDatabase(userId).getTotal();
    }

    private UnreadCountVO aggregateFromDatabase(Long userId) {
        List<java.util.Map<String, Object>> rows = messageReadMapper.countUnreadGroupByType(userId);
        Map<String, Long> byType = new LinkedHashMap<>();
        long total = 0L;
        for (java.util.Map<String, Object> row : rows) {
            Object type = row.get("type");
            Object cnt = row.get("cnt");
            if (type == null || cnt == null) {
                continue;
            }
            long count = ((Number) cnt).longValue();
            if (count <= 0) {
                continue;
            }
            byType.put(String.valueOf(type), count);
            total += count;
        }
        return UnreadCountVO.of(total, byType);
    }

    /** 分组明细每次都查库：它是次要信息，不值得为它维护第二份缓存 */
    private Map<String, Long> loadUnreadByType(Long userId) {
        return aggregateFromDatabase(userId).getByType();
    }

    private Long readCachedTotal(String key) {
        String value = redisTemplate.opsForValue().get(key);
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            // 脏缓存直接删掉，让下一次读走数据库
            redisTemplate.delete(key);
            return null;
        }
    }

    private void invalidateUnreadCache(Long userId) {
        redisTemplate.delete(RedisKeyConstants.messageUnread(userId));
    }

    /* ================================================================== */
    /* 内部工具：推送与校验                                                */
    /* ================================================================== */

    /** 新消息推给收件人的 SSE 通道；失败不影响已经被 {@code @Transactional} 保护的主流程 */
    private void pushNewMessage(Long receiverId, InternalMessage row) {
        try {
            Map<String, Object> data = new HashMap<>(6);
            data.put("messageId", row.getId());
            data.put("messageType", row.getType());
            data.put("title", row.getTitle());
            data.put("content", row.getContent());
            data.put("unreadCount", currentUnreadTotal(receiverId));
            sseHub.push(receiverId, MessageSseHub.EVENT_NEW_MESSAGE, data);
        } catch (Exception e) {
            log.warn("站内信推送失败，已忽略 | messageId={} | {}", row.getId(), e.getMessage());
        }
    }

    /**
     * 取出「我的」消息，否则抛错。
     *
     * <p>{@code 7001} 与 {@code 7002} 分开返回：前者是「这条消息不存在」，
     * 后者是「存在但不是你的」。合并成一个码会让调试越权问题时失去线索。
     * 但注意 —— 对攻击者来说这个区分本身就是信息泄露（可以用来探测
     * 某个 messageId 是否存在）。一期消息 ID 是自增的、本身可枚举，
     * 且不携带敏感内容，因此按文档要求保留区分。</p>
     */
    private InternalMessage requireMine(Long messageId, LoginUser me) {
        InternalMessage message = messageMapper.selectById(messageId);
        if (message == null) {
            throw new BusinessException(ResultCode.MESSAGE_NOT_FOUND);
        }
        if (!me.userId().equals(message.getReceiverId())) {
            throw new BusinessException(ResultCode.MESSAGE_NO_PERMISSION);
        }
        return message;
    }

    /**
     * 按日期区间筛选发送时间（闭区间）。
     *
     * <p>结束日用 {@code < 次日 0 点}，与 M4 的处理保持一致：
     * 将来列类型改成 {@code DATETIME(3)} 时，{@code 23:59:59.500} 这种边界值
     * 不会被静默漏掉。</p>
     */
    private void applyDateRange(LambdaQueryWrapper<InternalMessage> wrapper, String startDate, String endDate) {
        LocalDate start = parseDate(startDate, "startDate");
        LocalDate end = parseDate(endDate, "endDate");
        if (start != null && end != null && start.isAfter(end)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "开始日期不能晚于结束日期");
        }
        if (start != null) {
            wrapper.ge(InternalMessage::getCreateTime, start.atStartOfDay());
        }
        if (end != null) {
            wrapper.lt(InternalMessage::getCreateTime, end.plusDays(1).atStartOfDay());
        }
    }

    private static LocalDate parseDate(String value, String field) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw new BusinessException(ResultCode.PARAM_ERROR, field + " 日期格式应为 yyyy-MM-dd");
        }
    }
}

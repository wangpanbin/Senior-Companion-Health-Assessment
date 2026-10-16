package org.company.nianglin.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.company.nianglin.common.PageResult;
import org.company.nianglin.common.ResultCode;
import org.company.nianglin.constant.ChatMsgType;
import org.company.nianglin.constant.FileBizType;
import org.company.nianglin.constant.OrderStatus;
import org.company.nianglin.constant.RoleConstants;
import org.company.nianglin.dto.AdminChatOrderQuery;
import org.company.nianglin.dto.ChatConversationQuery;
import org.company.nianglin.dto.ChatReadDTO;
import org.company.nianglin.dto.ChatSendDTO;
import org.company.nianglin.entity.ChatMessage;
import org.company.nianglin.entity.CompanionOrder;
import org.company.nianglin.entity.CompanionProfile;
import org.company.nianglin.entity.SysFile;
import org.company.nianglin.entity.SysUser;
import org.company.nianglin.exception.BusinessException;
import org.company.nianglin.mapper.ChatMessageMapper;
import org.company.nianglin.mapper.ChatQueryMapper;
import org.company.nianglin.mapper.CompanionProfileMapper;
import org.company.nianglin.mapper.SysFileMapper;
import org.company.nianglin.mapper.SysUserMapper;
import org.company.nianglin.security.LoginUser;
import org.company.nianglin.security.SecurityUtils;
import org.company.nianglin.service.ChatService;
import org.company.nianglin.service.OrderService;
import org.company.nianglin.util.ComplianceCheckUtil;
import org.company.nianglin.util.MaskUtil;
import org.company.nianglin.vo.AdminChatOrderVO;
import org.company.nianglin.vo.ChatConversationVO;
import org.company.nianglin.vo.ChatHistoryVO;
import org.company.nianglin.vo.ChatMessageVO;
import org.company.nianglin.vo.ChatReadResultVO;
import org.company.nianglin.vo.ChatUnreadTotalVO;
import org.company.nianglin.websocket.ChatMessageHub;
import org.company.nianglin.websocket.MessageSseHub;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatServiceImpl implements ChatService {
    private static final int DEFAULT_HISTORY_SIZE = 20;
    private static final int MAX_HISTORY_SIZE = 100;
    private static final int CONTENT_MAX_LEN = 1000;

    private final OrderService orderService;
    private final ChatMessageMapper chatMessageMapper;
    private final CompanionProfileMapper companionProfileMapper;
    private final SysFileMapper sysFileMapper;
    private final SysUserMapper sysUserMapper;
    private final ChatMessageHub chatMessageHub;
    private final MessageSseHub messageSseHub;
    private final ChatQueryMapper chatQueryMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ChatMessageVO send(Long orderId, ChatSendDTO dto) {
        LoginUser me = SecurityUtils.currentUser();
        CompanionOrder order = orderService.requireInvolved(orderId, me);
        requireChatWriter(me, order);
        requireChatAvailable(order);
        ChatMsgType type = ChatMsgType.of(dto.getMsgType());
        if (type == null) {
            throw new BusinessException(ResultCode.CHAT_CONTENT_ILLEGAL);
        }
        String content = StringUtils.hasText(dto.getContent()) ? dto.getContent().trim() : null;
        validateContent(type, content);
        SysFile file = null;
        if (type.requiresFile()) {
            file = requireOwnedChatFile(dto.getFileId(), me);
            if (ChatMsgType.ofExt(file.getExt()) != type) {
                throw new BusinessException(ResultCode.CHAT_FILE_TYPE_MISMATCH);
            }
        }
        ChatMessage message = new ChatMessage();
        message.setOrderId(orderId);
        message.setSenderId(me.userId());
        message.setSenderRole(me.role());
        message.setMsgType(type.name());
        message.setContent(content);
        if (file != null) {
            message.setFileId(file.getFileId());
            message.setFileUrl(file.getUrl());
            message.setFileSize(file.getSize());
        }
        chatMessageMapper.insert(message);
        ChatMessageVO vo = ChatMessageVO.of(message);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                notifyPeer(order, me, vo);
            }
        });
        log.info("聊天消息已保存 | orderId={} | messageId={} | msgType={}", orderId, message.getId(), type);
        return vo;
    }

    private static void requireChatWriter(LoginUser me, CompanionOrder order) {
        boolean family = RoleConstants.FAMILY.equals(me.role()) && me.userId().equals(order.getFamilyId());
        boolean companion = RoleConstants.COMPANION.equals(me.role()) && me.userId().equals(order.getCompanionId());
        if (!family && !companion) {
            throw new BusinessException(ResultCode.CHAT_READ_ONLY);
        }
    }

    private static void requireChatAvailable(CompanionOrder order) {
        if (order.getCompanionId() == null) {
            throw new BusinessException(ResultCode.CHAT_NOT_AVAILABLE);
        }
    }

    private static void requireParticipantRole(LoginUser me) {
        if (!RoleConstants.FAMILY.equals(me.role()) && !RoleConstants.COMPANION.equals(me.role())) {
            throw new BusinessException(ResultCode.FORBIDDEN);
        }
    }

    private static void validateContent(ChatMsgType type, String content) {
        if (type == ChatMsgType.TEXT && !StringUtils.hasText(content)) {
            throw new BusinessException(ResultCode.CHAT_CONTENT_ILLEGAL, "消息内容不能为空");
        }
        if (content != null && (content.codePointCount(0, content.length()) > CONTENT_MAX_LEN
                || ComplianceCheckUtil.firstHit(content) != null)) {
            throw new BusinessException(ResultCode.CHAT_CONTENT_ILLEGAL);
        }
    }

    private SysFile requireOwnedChatFile(String fileId, LoginUser me) {
        if (!StringUtils.hasText(fileId)) {
            throw new BusinessException(ResultCode.CHAT_FILE_REQUIRED);
        }
        SysFile file = sysFileMapper.selectOne(Wrappers.lambdaQuery(SysFile.class).eq(SysFile::getFileId, fileId));
        if (file == null || !FileBizType.CHAT.name().equals(file.getBizType())
                || !me.userId().equals(file.getUploaderId())) {
            throw new BusinessException(ResultCode.CHAT_FILE_NOT_OWNED);
        }
        return file;
    }

    private void notifyPeer(CompanionOrder order, LoginUser me, ChatMessageVO vo) {
        try {
            chatMessageHub.publishToChat(order.getId(), me.userId(), ChatMessageHub.TYPE_CHAT_MESSAGE, vo);
        } catch (RuntimeException ex) {
            log.warn("聊天 WS 推送失败 | orderId={} | messageId={}", order.getId(), vo.getMessageId(), ex);
        }
        Map<String, Object> payload = new HashMap<>(4);
        payload.put("orderId", order.getId());
        payload.put("msgType", vo.getMsgType());
        payload.put("preview", ChatMsgType.previewOf(vo.getMsgType(), vo.getContent()));
        try {
            messageSseHub.push(resolvePeerUserId(order, me.userId()), MessageSseHub.EVENT_CHAT_MESSAGE, payload);
        } catch (RuntimeException ex) {
            log.warn("聊天 SSE 提醒失败 | orderId={} | messageId={}", order.getId(), vo.getMessageId(), ex);
        }
    }

    @Override
    public ChatHistoryVO history(Long orderId, Long cursor, Integer size) {
        LoginUser me = SecurityUtils.currentUser();
        CompanionOrder order = orderService.requireInvolved(orderId, me);
        requireChatAvailable(order);
        int limit = size == null || size < 1 ? DEFAULT_HISTORY_SIZE : Math.min(size, MAX_HISTORY_SIZE);
        long anchor = cursor == null || cursor <= 0 ? 0L : cursor;
        List<ChatMessage> rows = chatMessageMapper.selectList(Wrappers.lambdaQuery(ChatMessage.class)
                .eq(ChatMessage::getOrderId, orderId).lt(anchor > 0, ChatMessage::getId, anchor)
                .orderByDesc(ChatMessage::getId).last("LIMIT " + (limit + 1)));
        boolean hasMore = rows.size() > limit;
        List<ChatMessage> pageRows = hasMore ? rows.subList(0, limit) : rows;
        Long peerId = resolvePeerUserId(order, me.userId());
        ChatHistoryVO vo = new ChatHistoryVO()
                .setRecords(pageRows.stream().map(ChatMessageVO::of).toList())
                .setNextCursor(pageRows.isEmpty() ? null : pageRows.get(pageRows.size() - 1).getId())
                .setHasMore(hasMore).setPeerUserId(peerId).setPeerRole(resolvePeerRole(order, peerId))
                .setMyRole(me.role());
        if (peerId != null) {
            vo.setPeerName(MaskUtil.name(displayNames(List.of(peerId)).get(peerId)));
        }
        return vo;
    }

    @Override
    public PageResult<ChatConversationVO> conversations(ChatConversationQuery query) {
        LoginUser me = SecurityUtils.currentUser();
        requireParticipantRole(me);
        Page<CompanionOrder> page = chatQueryMapper.conversations(query.toMpPage(), me.userId(), me.role());
        List<CompanionOrder> orders = page.getRecords();
        if (orders.isEmpty()) return PageResult.of(page, List.of());
        List<Long> ids = orders.stream().map(CompanionOrder::getId).toList();
        Map<Long, ChatMessage> last = lastMessages(ids);
        Map<Long, Map<String, Object>> stats = messageStats(ids, me.userId());
        Map<Long, String> names = displayNames(relatedUserIds(orders));
        return PageResult.of(page, orders.stream().map(order -> {
            Long peerId = resolvePeerUserId(order, me.userId());
            return new ChatConversationVO().setOrderId(order.getId()).setOrderNo(order.getOrderNo())
                    .setOrderStatusLabel(OrderStatus.labelOf(order.getStatus())).setPeerUserId(peerId)
                    .setPeerRole(resolvePeerRole(order, peerId)).setPeerName(MaskUtil.name(names.get(peerId)))
                    .setHospital(order.getHospital()).setVisitTime(order.getVisitTime())
                    .setLastMessage(ChatConversationVO.lastOf(last.get(order.getId())))
                    .setUnreadCount((int) statValue(stats, order.getId(), "unreadCount"));
        }).toList());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ChatReadResultVO markRead(Long orderId, ChatReadDTO dto) {
        LoginUser me = SecurityUtils.currentUser();
        CompanionOrder order = orderService.requireInvolved(orderId, me);
        requireChatWriter(me, order);
        requireChatAvailable(order);
        long effective = Math.min(dto.getLastMessageId(), chatQueryMapper.maxMessageId(orderId));
        chatQueryMapper.advanceReadCursor(orderId, me.userId(), effective);
        return new ChatReadResultVO().setUnreadCount((int) chatQueryMapper.unreadCount(orderId, me.userId()));
    }

    @Override
    public ChatUnreadTotalVO unreadTotal() {
        LoginUser me = SecurityUtils.currentUser();
        requireParticipantRole(me);
        return new ChatUnreadTotalVO().setTotal((int) chatQueryMapper.unreadTotal(me.userId(), me.role()));
    }

    @Override
    public PageResult<AdminChatOrderVO> adminChatOrders(AdminChatOrderQuery query) {
        if (!SecurityUtils.currentUser().isAdmin()) throw new BusinessException(ResultCode.FORBIDDEN);
        String keyword = StringUtils.hasText(query.getKeyword()) ? query.getKeyword().trim() : null;
        Page<CompanionOrder> page = chatQueryMapper.adminOrders(query.toMpPage(), keyword);
        List<CompanionOrder> orders = page.getRecords();
        if (orders.isEmpty()) return PageResult.of(page, List.of());
        List<Long> ids = orders.stream().map(CompanionOrder::getId).toList();
        Map<Long, ChatMessage> last = lastMessages(ids);
        Map<Long, Map<String, Object>> stats = messageStats(ids, SecurityUtils.currentUserId());
        Map<Long, String> names = displayNames(relatedUserIds(orders));
        return PageResult.of(page, orders.stream().map(order -> new AdminChatOrderVO()
                .setOrderId(order.getId()).setOrderNo(order.getOrderNo())
                .setOrderStatusLabel(OrderStatus.labelOf(order.getStatus()))
                .setFamilyName(MaskUtil.name(names.get(order.getFamilyId())))
                .setCompanionName(MaskUtil.name(names.get(order.getCompanionId())))
                .setHospital(order.getHospital()).setMessageCount(statValue(stats, order.getId(), "messageCount"))
                .setLastMessageTime(last.get(order.getId()).getCreateTime())).toList());
    }

    private Map<Long, ChatMessage> lastMessages(List<Long> orderIds) {
        Map<Long, ChatMessage> result = new HashMap<>(orderIds.size());
        for (ChatMessage message : chatQueryMapper.lastMessages(orderIds)) result.put(message.getOrderId(), message);
        return result;
    }

    private Map<Long, Map<String, Object>> messageStats(List<Long> orderIds, Long userId) {
        Map<Long, Map<String, Object>> result = new HashMap<>(orderIds.size());
        for (Map<String, Object> row : chatQueryMapper.messageStats(orderIds, userId)) {
            result.put(((Number) row.get("orderId")).longValue(), row);
        }
        return result;
    }

    private static long statValue(Map<Long, Map<String, Object>> stats, Long orderId, String field) {
        Map<String, Object> row = stats.get(orderId);
        return row == null ? 0L : ((Number) row.get(field)).longValue();
    }

    /**
     * 会话「对端」只在双方之间成立：家属↔陪诊员互为对端。
     * ELDER / ADMIN 是只读旁观者（ADR-0011），他们面前同时站着两方，
     * 硬指一方会把家属显示成陪诊员 —— 故返回 null，由上层留空、前端回落到「订单沟通」。
     */
    private static Long resolvePeerUserId(CompanionOrder order, Long myUserId) {
        if (myUserId.equals(order.getFamilyId())) {
            return order.getCompanionId();
        }
        if (myUserId.equals(order.getCompanionId())) {
            return order.getFamilyId();
        }
        return null;
    }

    private static String resolvePeerRole(CompanionOrder order, Long peerUserId) {
        if (peerUserId == null) {
            return null;
        }
        return peerUserId.equals(order.getCompanionId()) ? RoleConstants.COMPANION : RoleConstants.FAMILY;
    }

    private static Collection<Long> relatedUserIds(List<CompanionOrder> orders) {
        Collection<Long> ids = new HashSet<>(orders.size() * 2);
        for (CompanionOrder order : orders) {
            ids.add(order.getFamilyId());
            ids.add(order.getCompanionId());
        }
        return ids;
    }

    private Map<Long, String> displayNames(Collection<Long> userIds) {
        Map<Long, String> result = new HashMap<>(userIds.size());
        for (SysUser user : sysUserMapper.selectBatchIds(userIds)) {
            String name = StringUtils.hasText(user.getRealName()) ? user.getRealName()
                    : StringUtils.hasText(user.getNickname()) ? user.getNickname() : user.getUsername();
            result.put(user.getId(), name);
        }
        for (CompanionProfile profile : companionProfileMapper.selectList(
                Wrappers.lambdaQuery(CompanionProfile.class).in(CompanionProfile::getUserId, userIds))) {
            if (StringUtils.hasText(profile.getRealName())) result.put(profile.getUserId(), profile.getRealName());
        }
        return result;
    }
}

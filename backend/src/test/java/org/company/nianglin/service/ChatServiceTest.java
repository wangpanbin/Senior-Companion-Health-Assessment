package org.company.nianglin.service;

import org.company.nianglin.common.ResultCode;
import org.company.nianglin.dto.ChatSendDTO;
import org.company.nianglin.entity.ChatMessage;
import org.company.nianglin.entity.CompanionOrder;
import org.company.nianglin.entity.SysFile;
import org.company.nianglin.exception.BusinessException;
import org.company.nianglin.mapper.ChatMessageMapper;
import org.company.nianglin.mapper.CompanionProfileMapper;
import org.company.nianglin.mapper.SysFileMapper;
import org.company.nianglin.mapper.SysUserMapper;
import org.company.nianglin.security.LoginUser;
import org.company.nianglin.service.impl.ChatServiceImpl;
import org.company.nianglin.support.MybatisLambdaCache;
import org.company.nianglin.websocket.ChatMessageHub;
import org.company.nianglin.websocket.MessageSseHub;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("订单聊天：归属、事务提交与内容边界")
class ChatServiceTest {
    @Mock private OrderService orderService;
    @Mock private ChatMessageMapper messages;
    @Mock private CompanionProfileMapper profiles;
    @Mock private SysFileMapper files;
    @Mock private SysUserMapper users;
    @Mock private ChatMessageHub hub;
    @Mock private MessageSseHub sse;
    @Mock private org.company.nianglin.mapper.ChatQueryMapper queries;
    private ChatServiceImpl service;
    private CompanionOrder order;

    @BeforeEach
    void setUp() {
        MybatisLambdaCache.warmUp();
        service = new ChatServiceImpl(orderService, messages, profiles, files, users, hub, sse, queries);
        order = new CompanionOrder();
        order.setId(101L);
        order.setFamilyId(11L);
        order.setCompanionId(22L);
        order.setStatus("ACCEPTED");
        login("FAMILY", 11L);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("未接单订单不可读取会话")
    void historyShouldRejectUnassignedOrder() {
        order.setCompanionId(null);
        involved();
        assertCode(ResultCode.CHAT_NOT_AVAILABLE, () -> service.history(101L, 0L, 20));
        verifyNoInteractions(messages);
    }

    @Test
    @DisplayName("旁观视角（ELDER）读历史不虚构对端，peer 三字段一律留空")
    void historyShouldLeavePeerBlankForBystanderViewer() {
        login("ELDER", 33L);
        involved();
        when(messages.selectList(any())).thenReturn(java.util.List.of());
        org.company.nianglin.entity.SysUser family = new org.company.nianglin.entity.SysUser();
        family.setId(11L);
        family.setUsername("family01");
        lenient().when(users.selectBatchIds(any())).thenReturn(java.util.List.of(family));
        lenient().when(profiles.selectList(any())).thenReturn(java.util.List.of());

        org.company.nianglin.vo.ChatHistoryVO vo = service.history(101L, 0L, 20);

        assertNull(vo.getPeerUserId());
        assertNull(vo.getPeerRole());
        assertNull(vo.getPeerName());
        assertEquals("ELDER", vo.getMyRole());
    }

    @Test
    @DisplayName("管理员无论消息内容如何都没有写权限")
    void sendShouldCheckWriterBeforeContent() {
        login("ADMIN", 1L);
        involved();
        assertCode(ResultCode.CHAT_READ_ONLY, () -> service.send(101L, dto("INVALID", "内容")));
        verifyNoInteractions(messages);
    }

    @Test
    @DisplayName("家属角色不能以陪诊员的订单关联获得写权限")
    void sendShouldBindRoleToCorrespondingOrderOwner() {
        login("FAMILY", 22L);
        involved();
        assertCode(ResultCode.CHAT_READ_ONLY, () -> service.send(101L, dto("TEXT", "已到医院")));
        verifyNoInteractions(messages);
    }

    @Test
    @DisplayName("发送仅在事务提交后推送")
    void sendShouldPublishOnlyAfterCommit() {
        involved();
        stored();
        TransactionSynchronizationManager.initSynchronization();
        assertEquals(900L, service.send(101L, dto("TEXT", "已到医院")).getMessageId());
        verifyNoInteractions(hub, sse);
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        verify(hub).publishToChat(eq(101L), eq(11L), eq("CHAT_MESSAGE"), argThat(data ->
                data instanceof java.util.Map<?, ?> map && Long.valueOf(900).equals(map.get("messageId"))));
        verify(sse).push(eq(22L), eq("CHAT_MESSAGE"), any());
    }

    @Test
    @DisplayName("回滚不推送尚未持久化的消息")
    void sendShouldNotPublishAfterRollback() {
        involved();
        stored();
        TransactionSynchronizationManager.initSynchronization();
        service.send(101L, dto("TEXT", "已到医院"));
        TransactionSynchronizationManager.getSynchronizations().forEach(sync ->
                sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        verifyNoInteractions(hub, sse);
    }

    @Test
    @DisplayName("WebSocket失败不阻断已提交响应和SSE提醒")
    void sendShouldIsolatePushFailure() {
        involved();
        stored();
        TransactionSynchronizationManager.initSynchronization();
        when(hub.publishToChat(anyLong(), anyLong(), anyString(), any())).thenThrow(new IllegalStateException("closed"));
        service.send(101L, dto("TEXT", "已到医院"));
        assertDoesNotThrow(() -> TransactionSynchronizationManager.getSynchronizations()
                .forEach(TransactionSynchronization::afterCommit));
        verify(sse).push(eq(22L), eq("CHAT_MESSAGE"), any());
    }

    @Test
    @DisplayName("文字长度按Unicode字符计数，允许一千个非BMP汉字")
    void sendShouldAcceptThousandUnicodeCodePoints() {
        involved();
        stored();
        TransactionSynchronizationManager.initSynchronization();
        String text = "\uD840\uDC00".repeat(1000);
        assertEquals(text, service.send(101L, dto("TEXT", text)).getContent());
    }

    @Test
    @DisplayName("一千零一个字符拒绝且不落库")
    void sendShouldRejectOverlongContent() {
        involved();
        assertCode(ResultCode.CHAT_CONTENT_ILLEGAL, () -> service.send(101L, dto("TEXT", "字".repeat(1001))));
        verifyNoInteractions(messages);
    }

    @Test
    @DisplayName("已取消订单仍可继续交涉")
    void sendShouldAllowConversationAfterCancellation() {
        order.setStatus("CANCELLED");
        involved();
        stored();
        TransactionSynchronizationManager.initSynchronization();
        assertNotNull(service.send(101L, dto("TEXT", "退款凭证已发")));
    }

    @Test
    @DisplayName("图片消息没有附件时返回缺附件")
    void mediaSendShouldRequireFileId() {
        involved();
        assertCode(ResultCode.CHAT_FILE_REQUIRED, () -> service.send(101L, dto("IMAGE", null)));
        verifyNoInteractions(messages);
    }

    @Test
    @DisplayName("附件必须属于发送人且是聊天附件")
    void mediaSendShouldRejectForeignFile() {
        involved();
        when(files.selectOne(any())).thenReturn(file("CHAT", "png", 22L));
        assertCode(ResultCode.CHAT_FILE_NOT_OWNED, () -> service.send(101L, imageDto("f_1")));
        verifyNoInteractions(messages);
    }

    @Test
    @DisplayName("视频消息引用PNG附件时类型不匹配")
    void mediaSendShouldRejectMismatchedExtension() {
        involved();
        when(files.selectOne(any())).thenReturn(file("CHAT", "png", 11L));
        ChatSendDTO dto = new ChatSendDTO();
        dto.setMsgType("VIDEO");
        dto.setFileId("f_1");
        assertCode(ResultCode.CHAT_FILE_TYPE_MISMATCH, () -> service.send(101L, dto));
        verifyNoInteractions(messages);
    }

    @Test
    @DisplayName("聊天正文命中医疗违规词时拒绝")
    void sendShouldRejectCompliancePhrase() {
        involved();
        assertCode(ResultCode.CHAT_CONTENT_ILLEGAL, () -> service.send(101L, dto("TEXT", "老人今天诊断为感冒")));
        verifyNoInteractions(messages);
    }

    private void involved() {
        when(orderService.requireInvolved(eq(101L), any(LoginUser.class))).thenReturn(order);
    }

    private void stored() {
        when(messages.insert(any(ChatMessage.class))).thenAnswer(invocation -> {
            ChatMessage message = invocation.getArgument(0);
            message.setId(900L);
            message.setCreateTime(java.time.LocalDateTime.of(2026, 10, 16, 10, 0));
            return 1;
        });
    }

    private static void login(String role, Long id) {
        LoginUser user = new LoginUser(id, "test-user", role, 0, "test-jti", Long.MAX_VALUE);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, user.authorities()));
    }

    private static ChatSendDTO dto(String type, String content) {
        ChatSendDTO dto = new ChatSendDTO();
        dto.setMsgType(type);
        dto.setContent(content);
        return dto;
    }

    private static ChatSendDTO imageDto(String fileId) {
        ChatSendDTO dto = new ChatSendDTO();
        dto.setMsgType("IMAGE");
        dto.setFileId(fileId);
        return dto;
    }

    private static SysFile file(String bizType, String ext, Long uploaderId) {
        SysFile file = new SysFile();
        file.setFileId("f_1");
        file.setBizType(bizType);
        file.setExt(ext);
        file.setUploaderId(uploaderId);
        return file;
    }

    private static void assertCode(ResultCode code, org.junit.jupiter.api.function.Executable action) {
        assertEquals(code.getCode(), assertThrows(BusinessException.class, action).getCode());
    }
}

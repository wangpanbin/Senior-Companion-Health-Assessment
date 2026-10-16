package org.company.nianglin.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.company.nianglin.dto.ChatReadDTO;
import org.company.nianglin.entity.CompanionOrder;
import org.company.nianglin.mapper.CompanionOrderMapper;
import org.company.nianglin.service.ChatService;
import org.company.nianglin.support.TestTokens;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("订单聊天：真实数据库、并发游标与四角色权限矩阵")
class ChatAccessMatrixTest {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private CompanionOrderMapper orders;
    @Autowired private ChatService service;
    @Autowired private JwtTokenProvider tokens;
    @Autowired private TokenStore tokenStore;
    private Long orderId;

    @BeforeEach
    void setUp() {
        CompanionOrder order = new CompanionOrder();
        order.setOrderNo("CHAT" + UUID.randomUUID().toString().replace("-", "").substring(0, 24));
        order.setFamilyId(101L);
        order.setElderId(401L);
        order.setCompanionId(307L);
        order.setHospital("聊天测试医院");
        order.setDepartment("测试科室");
        order.setVisitTime(LocalDateTime.of(2099, 1, 1, 10, 0));
        order.setAddress("测试地址");
        order.setStatus("ACCEPTED");
        orders.insert(order);
        orderId = order.getId();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        if (orderId != null) {
            jdbc.update("DELETE FROM chat_read_cursor WHERE order_id = ?", orderId);
            jdbc.update("DELETE FROM chat_message WHERE order_id = ?", orderId);
            jdbc.update("DELETE FROM companion_order WHERE id = ?", orderId);
        }
    }

    @ParameterizedTest
    @CsvSource({"FAMILY,101", "COMPANION,307", "ELDER,201", "ADMIN,1"})
    @DisplayName("四类本单相关方都可读历史")
    void historyShouldAllowInvolvedRoles(String role, long id) throws Exception {
        mvc.perform(get("/api/chat/{id}/messages", orderId).header("Authorization", bearer(id, role)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.myRole").value(role));
    }

    @ParameterizedTest
    @CsvSource({"FAMILY,102", "COMPANION,308", "ELDER,202"})
    @DisplayName("非相关方不能读取或写入订单聊天")
    void historyAndSendShouldRejectOtherOwners(String role, long id) throws Exception {
        String bearer = bearer(id, role);
        mvc.perform(get("/api/chat/{id}/messages", orderId).header("Authorization", bearer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(3004));
        if (!role.equals("ELDER")) {
            mvc.perform(post("/api/chat/{id}/messages", orderId).header("Authorization", bearer)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"msgType\":\"TEXT\",\"content\":\"到院\"}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(3004));
        }
    }

    @ParameterizedTest
    @CsvSource({"ELDER,201,403", "ADMIN,1,7008"})
    @DisplayName("老人和管理员不可发送或上报已读")
    void writesShouldRejectReadOnlyRoles(String role, long id, int code) throws Exception {
        String bearer = bearer(id, role);
        for (String action : List.of("messages", "read")) {
            String body = action.equals("read") ? "{\"lastMessageId\":0}" : "{\"msgType\":\"TEXT\",\"content\":\"到院\"}";
            mvc.perform(post("/api/chat/{id}/" + action, orderId).header("Authorization", bearer)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().is(code == 403 ? 403 : 200)).andExpect(jsonPath("$.code").value(code));
        }
    }

    @ParameterizedTest
    @CsvSource({"FAMILY,101,true", "COMPANION,307,true", "ELDER,201,false", "ADMIN,1,false"})
    @DisplayName("仅家属和陪诊员具有个人会话列表和未读总数")
    void personalListsShouldAllowOnlyWriters(String role, long id, boolean allowed) throws Exception {
        for (String path : List.of("conversations", "unread-total")) {
            mvc.perform(get("/api/chat/" + path).header("Authorization", bearer(id, role)))
                    .andExpect(status().is(allowed ? 200 : 403));
        }
    }

    @ParameterizedTest
    @CsvSource({"FAMILY,101,403", "COMPANION,307,403", "ELDER,201,403", "ADMIN,1,200"})
    @DisplayName("仲裁检索仅管理员可访问")
    void adminOrdersShouldRequireAdmin(String role, long id, int status) throws Exception {
        mvc.perform(get("/api/admin/chat/orders").header("Authorization", bearer(id, role)))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().is(status));
    }

    @Test
    @DisplayName("HTTP超长内容返回聊天错误码而非通用参数错误")
    void sendShouldReturn7004ForOverlongContent() throws Exception {
        mvc.perform(post("/api/chat/{id}/messages", orderId).header("Authorization", bearer(101, "FAMILY"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("msgType", "TEXT", "content", "字".repeat(1001)))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(7004));
    }

    @Test
    @DisplayName("发送、倒序游标、未读排除本人、已读不倒退")
    void messagesShouldRoundTripAndKeepReadCursorMonotonic() throws Exception {
        long first = send("FAMILY", 101, "我们已到医院");
        long second = send("COMPANION", 307, "我在入口等候");
        mvc.perform(get("/api/chat/conversations").param("size", "1").header("Authorization", bearer(101, "FAMILY")))
                .andExpect(jsonPath("$.data.records.length()").value(1))
                .andExpect(jsonPath("$.data.records[0].orderId").value(orderId))
                .andExpect(jsonPath("$.data.records[0].unreadCount").value(1))
                .andExpect(jsonPath("$.data.records[0].lastMessage.preview").value("我在入口等候"));
        mvc.perform(get("/api/admin/chat/orders").param("keyword", orders.selectById(orderId).getOrderNo())
                        .header("Authorization", bearer(1, "ADMIN")))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].messageCount").value(2));
        var history = mvc.perform(get("/api/chat/{id}/messages", orderId).param("size", "1")
                        .header("Authorization", bearer(101, "FAMILY")))
                .andExpect(jsonPath("$.data.records[0].messageId").value(second))
                .andExpect(jsonPath("$.data.hasMore").value(true)).andReturn();
        long cursor = json.readTree(history.getResponse().getContentAsString()).at("/data/nextCursor").asLong();
        mvc.perform(get("/api/chat/{id}/messages", orderId).param("cursor", Long.toString(cursor))
                        .header("Authorization", bearer(101, "FAMILY")))
                .andExpect(jsonPath("$.data.records[0].messageId").value(first))
                .andExpect(jsonPath("$.data.hasMore").value(false));
        long[] readIds = {first, second, first, Long.MAX_VALUE};
        int[] expectedUnread = {1, 0, 0, 0};
        for (int i = 0; i < readIds.length; i++) {
            mvc.perform(post("/api/chat/{id}/read", orderId).header("Authorization", bearer(101, "FAMILY"))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"lastMessageId\":" + readIds[i] + "}"))
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.unreadCount").value(expectedUnread[i]));
        }
        assertEquals(second, jdbc.queryForObject("SELECT last_read_message_id FROM chat_read_cursor WHERE order_id=? AND user_id=101", Long.class, orderId));
    }

    @Test
    @DisplayName("多连接并发首次已读上报不产生重复键且只前进")
    void markReadShouldAtomicallyUpsertConcurrentReports() throws Exception {
        long first = send("COMPANION", 307, "已到入口");
        long last = send("COMPANION", 307, "请慢慢走");
        CountDownLatch ready = new CountDownLatch(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>(8);
        try (var executor = Executors.newFixedThreadPool(8)) {
            for (int i = 0; i < 8; i++) {
                long anchor = i % 2 == 0 ? first : last;
                futures.add(executor.submit(() -> {
                    LoginUser user = new LoginUser(101L, "chat-test", "FAMILY", 0, "test", Long.MAX_VALUE);
                    SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null, user.authorities()));
                    ready.countDown();
                    try {
                        if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("start timeout");
                        ChatReadDTO dto = new ChatReadDTO();
                        dto.setLastMessageId(anchor);
                        service.markRead(orderId, dto);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(ex);
                    } finally {
                        SecurityContextHolder.clearContext();
                    }
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            for (Future<?> future : futures) future.get(30, TimeUnit.SECONDS);
        }
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM chat_read_cursor WHERE order_id=? AND user_id=101", Integer.class, orderId));
        assertEquals(last, jdbc.queryForObject("SELECT last_read_message_id FROM chat_read_cursor WHERE order_id=? AND user_id=101", Long.class, orderId));
    }

    private long send(String role, long userId, String text) throws Exception {
        var response = mvc.perform(post("/api/chat/{id}/messages", orderId).header("Authorization", bearer(userId, role))
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("msgType", "TEXT", "content", text))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200)).andReturn();
        return json.readTree(response.getResponse().getContentAsString()).at("/data/messageId").asLong();
    }

    private String bearer(long id, String role) {
        return TestTokens.bearer(tokens, tokenStore, id, role, "chat-matrix");
    }
}

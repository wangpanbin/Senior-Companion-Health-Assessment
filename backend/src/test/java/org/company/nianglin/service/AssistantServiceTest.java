package org.company.nianglin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.company.nianglin.common.PageResult;
import org.company.nianglin.common.ResultCode;
import org.company.nianglin.config.AssistantProperties;
import org.company.nianglin.dto.AssistantChatDTO;
import org.company.nianglin.dto.AssistantHistoryTurn;
import org.company.nianglin.exception.BusinessException;
import org.company.nianglin.security.LoginUser;
import org.company.nianglin.service.impl.AssistantServiceImpl;
import org.company.nianglin.service.support.AssistantFallbackFaq;
import org.company.nianglin.vo.AssistantChatVO;
import org.company.nianglin.vo.CompanionMatchVO;
import org.company.nianglin.vo.ElderVO;
import org.company.nianglin.vo.OrderRecommendVO;
import org.company.nianglin.vo.OrderVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * V3.7 家属智能助手 · Service 单测。
 *
 * <h3>为什么用 JDK 自带的 {@link HttpServer} 模拟 Python 服务</h3>
 *
 * <p>门面与 Python 之间是真实的 HTTP 调用。用 mock 掉 {@code RestTemplate}
 * 就等于把「URL 拼错 / 超时没生效 / 响应体解析不了」这些真实故障全部挡在测试之外 ——
 * 而这些恰恰是兜底逻辑存在的理由。
 * {@code com.sun.net.httpserver} 是 JDK 自带的，不需要任何新依赖。</p>
 *
 * <h3>锁住的行为</h3>
 * <ol>
 *   <li>上下文装配：订单、候选陪诊员、老人列表、最近订单都进 payload；</li>
 *   <li>归属校验：他人订单 → 3004，他人老人 → 2006（**不吞掉**）；</li>
 *   <li>限流：超限 → 10001；Redis 挂了 → 放行；</li>
 *   <li>兜底：Python 不可用 → 本地 FAQ 原文；兜底也失败 → 10002；</li>
 *   <li>违规问题：Python 回 400 ILLEGAL_QUESTION → 10003（**不走兜底**）。</li>
 * </ol>
 *
 * @author 银龄伴诊团队
 * @since V3.7
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("V3.7 家属智能助手：上下文装配、限流与兜底")
class AssistantServiceTest {

    @Mock
    private OrderService orderService;
    @Mock
    private ElderService elderService;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOps;

    private AssistantProperties properties;
    private AssistantFallbackFaq fallbackFaq;
    private AssistantServiceImpl service;
    private HttpServer pythonServer;
    private final List<String> receivedBodies = new ArrayList<>();

    @BeforeEach
    void setUp() {
        properties = new AssistantProperties();
        fallbackFaq = new AssistantFallbackFaq(properties, new ObjectMapper());
        service = new AssistantServiceImpl(orderService, elderService, redisTemplate, properties, fallbackFaq);
        login("FAMILY", 101L);
        // 限流计数默认放行；各用例按需覆盖
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
        lenient().when(valueOps.increment(anyString())).thenReturn(1L);
        // 上下文装配默认取到空列表
        lenient().when(elderService.page(any())).thenReturn(PageResult.empty(1L, 20L));
        lenient().when(orderService.myOrders(any())).thenReturn(PageResult.empty(1L, 5L));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        if (pythonServer != null) {
            pythonServer.stop(0);
            pythonServer = null;
        }
    }

    /* ================================================================== */
    /* 1. 上下文装配                                                        */
    /* ================================================================== */

    @Test
    @DisplayName("payload 里带上问题、历史、老人列表与最近订单")
    void payloadShouldCarryQuestionHistoryEldersAndOrders() throws Exception {
        startPython(200, """
                {"answer":"好的","intent":"FAQ_PLATFORM","actions":[],"sources":[],"degraded":true}
                """);

        ElderVO elder = new ElderVO();
        elder.setId(401L);
        elder.setName("张*");
        when(elderService.page(any())).thenReturn(new PageResult<>(1L, 1L, 20L, 1L, List.of(elder)));

        OrderVO recent = new OrderVO();
        recent.setId(1001L);
        recent.setOrderNo("NL20261021000001");
        recent.setStatus("PENDING");
        recent.setStatusLabel("待接单");
        recent.setVisitTime(LocalDateTime.of(2026, 10, 28, 9, 30));
        when(orderService.myOrders(any())).thenReturn(new PageResult<>(1L, 1L, 5L, 1L, List.of(recent)));

        AssistantChatDTO dto = new AssistantChatDTO();
        dto.setQuestion("陪诊费多少钱");
        AssistantHistoryTurn turn = new AssistantHistoryTurn();
        turn.setRole("user");
        turn.setContent("你好");
        dto.setHistory(List.of(turn));

        AssistantChatVO vo = service.chat(dto);

        assertEquals("好的", vo.getAnswer());
        assertEquals("FAQ_PLATFORM", vo.getIntent());
        assertTrue(vo.getDegraded());

        String body = receivedBodies.get(0);
        assertTrue(body.contains("\"question\":\"陪诊费多少钱\""), "payload 应带问题：" + body);
        assertTrue(body.contains("\"elders\""), "payload 应带老人列表：" + body);
        assertTrue(body.contains("张*"), "payload 应带脱敏老人名：" + body);
        assertTrue(body.contains("\"recentOrders\""), "payload 应带最近订单：" + body);
        assertTrue(body.contains("NL20261021000001"), "payload 应带订单号：" + body);
        assertTrue(body.contains("\"history\""), "payload 应带历史：" + body);
        // 令牌绝不能出现在 payload 里
        assertFalse(body.contains("Bearer"), "payload 不该携带任何令牌");
    }

    @Test
    @DisplayName("给了 orderId → 装配订单上下文与候选陪诊员（候选原样来自规则引擎）")
    void chatShouldAssembleOrderAndCandidates() throws Exception {
        startPython(200, """
                {"answer":"好的","intent":"RECOMMEND_COMPANION","actions":[],"sources":[],"degraded":true}
                """);

        OrderVO order = new OrderVO();
        order.setId(1001L);
        order.setOrderNo("NL20261021000001");
        order.setStatus("PENDING");
        order.setStatusLabel("待接单");
        order.setHospital("海南省人民医院");
        order.setDepartment("心内科");
        order.setVisitTime(LocalDateTime.of(2026, 10, 28, 9, 30));
        when(orderService.detail(1001L)).thenReturn(order);

        CompanionMatchVO candidate = new CompanionMatchVO();
        candidate.setCompanionId(301L);
        candidate.setCompanionName("李*军");
        candidate.setTotalScore(86);
        candidate.setDistanceScore(0);
        candidate.setReasons(List.of("同服务区域"));
        candidate.setNotes(List.of("未填写坐标，距离维度记 0"));
        OrderRecommendVO recommend = new OrderRecommendVO();
        recommend.setOrderId(1001L);
        recommend.setTotal(1);
        recommend.setCandidates(List.of(candidate));
        when(orderService.recommendCompanions(eq(1001L), anyInt())).thenReturn(recommend);

        AssistantChatDTO dto = new AssistantChatDTO();
        dto.setQuestion("推荐个陪诊员");
        dto.setOrderId(1001L);

        service.chat(dto);

        String body = receivedBodies.get(0);
        assertTrue(body.contains("海南省人民医院"), "payload 应带订单上下文：" + body);
        assertTrue(body.contains("\"candidates\""), "payload 应带候选：" + body);
        assertTrue(body.contains("李*军"), "payload 应带候选陪诊员：" + body);
        // 三段式可解释结构必须原样传递（ADR-0016）
        assertTrue(body.contains("未填写坐标，距离维度记 0"), "降级说明必须原样传递：" + body);
    }

    @Test
    @DisplayName("候选取不到不影响其它回答（候选是锦上添花）")
    void candidateFailureShouldNotBreakAnswer() throws Exception {
        startPython(200, """
                {"answer":"好的","intent":"RECOMMEND_COMPANION","actions":[],"sources":[],"degraded":true}
                """);

        OrderVO order = new OrderVO();
        order.setId(1001L);
        order.setStatus("PENDING");
        when(orderService.detail(1001L)).thenReturn(order);
        when(orderService.recommendCompanions(eq(1001L), anyInt()))
                .thenThrow(new RuntimeException("规则引擎炸了"));

        AssistantChatDTO dto = new AssistantChatDTO();
        dto.setQuestion("推荐个陪诊员");
        dto.setOrderId(1001L);

        AssistantChatVO vo = service.chat(dto);

        assertEquals("好的", vo.getAnswer());
        assertTrue(receivedBodies.get(0).contains("\"candidates\":[]"), "候选取不到时应为空数组");
    }

    /* ================================================================== */
    /* 2. 归属校验                                                        */
    /* ================================================================== */

    @Test
    @DisplayName("他人的订单 → 3004，且**不**被吞成兜底")
    void foreignOrderShouldPropagate3004() {
        when(orderService.detail(1001L))
                .thenThrow(new BusinessException(ResultCode.ORDER_NO_PERMISSION));

        AssistantChatDTO dto = new AssistantChatDTO();
        dto.setQuestion("我的订单到哪了");
        dto.setOrderId(1001L);

        BusinessException e = assertThrows(BusinessException.class, () -> service.chat(dto));
        assertEquals(ResultCode.ORDER_NO_PERMISSION.getCode(), e.getCode());
    }

    @Test
    @DisplayName("他人的老人档案 → 2006")
    void foreignElderShouldPropagate2006() {
        when(elderService.detail(401L))
                .thenThrow(new BusinessException(ResultCode.NO_PERMISSION_FOR_ELDER));

        AssistantChatDTO dto = new AssistantChatDTO();
        dto.setQuestion("帮我约个陪诊员");
        dto.setElderId(401L);

        BusinessException e = assertThrows(BusinessException.class, () -> service.chat(dto));
        assertEquals(ResultCode.NO_PERMISSION_FOR_ELDER.getCode(), e.getCode());
    }

    /* ================================================================== */
    /* 3. 限流                                                            */
    /* ================================================================== */

    @Test
    @DisplayName("超过每分钟上限 → 10001")
    void rateLimitExceededShouldThrow10001() {
        when(valueOps.increment(anyString())).thenReturn(11L);

        AssistantChatDTO dto = new AssistantChatDTO();
        dto.setQuestion("陪诊费多少钱");

        BusinessException e = assertThrows(BusinessException.class, () -> service.chat(dto));
        assertEquals(ResultCode.ASSISTANT_RATE_LIMITED.getCode(), e.getCode());
    }

    @Test
    @DisplayName("Redis 不可用 → 放行（限流是成本保护，不是安全边界）")
    void redisFailureShouldNotBlock() throws Exception {
        startPython(200, """
                {"answer":"好的","intent":"FAQ_PLATFORM","actions":[],"sources":[],"degraded":true}
                """);
        when(valueOps.increment(anyString())).thenThrow(new RuntimeException("redis down"));

        AssistantChatDTO dto = new AssistantChatDTO();
        dto.setQuestion("陪诊费多少钱");

        AssistantChatVO vo = service.chat(dto);
        assertEquals("好的", vo.getAnswer());
    }

    /* ================================================================== */
    /* 4. 兜底                                                            */
    /* ================================================================== */

    @Test
    @DisplayName("Python 不可用 → 走本地 FAQ 兜底（返回标准答案原文，degraded=true）")
    void pythonDownShouldFallbackToLocalFaq() throws Exception {
        // 起一个服务再立刻停掉 → 端口确定是「连不上」而不是「连上了但慢」
        startPython(200, "{}");
        stopPython();

        AssistantChatDTO dto = new AssistantChatDTO();
        dto.setQuestion("陪诊费多少钱");

        AssistantChatVO vo = service.chat(dto);

        assertNotNull(vo.getAnswer());
        assertFalse(vo.getAnswer().isBlank(), "兜底也该给出答案原文");
        assertEquals("FAQ_PLATFORM", vo.getIntent());
        assertTrue(vo.getDegraded(), "兜底必须标记 degraded");
        assertFalse(vo.getSources().isEmpty(), "兜底也要给依据引用");
        // 依据引用用 § 章节号，不漏出 Markdown 标题符号（与 Python 侧同口径）
        assertTrue(vo.getSources().get(0).contains("§"), "依据引用应含章节号：" + vo.getSources());
        assertFalse(vo.getSources().get(0).contains("##"), "依据引用不该漏出标题符号：" + vo.getSources());
    }

    @Test
    @DisplayName("payload **不外发**订单地址与未脱敏的陪诊员姓名（隐私最小化）")
    void payloadShouldNotLeakAddressOrFullCompanionName() throws Exception {
        startPython(200, """
                {"answer":"好的","intent":"RECOMMEND_COMPANION","actions":[],"sources":[],"degraded":true}
                """);

        OrderVO order = new OrderVO();
        order.setId(1001L);
        order.setStatus("PENDING");
        order.setHospital("海南省人民医院");
        order.setDepartment("心内科");
        order.setAddress("海南省海口市秀英区秀华路19号 门诊大楼3楼");
        order.setCompanionName("李建军");
        when(orderService.detail(1001L)).thenReturn(order);

        AssistantChatDTO dto = new AssistantChatDTO();
        dto.setQuestion("推荐个陪诊员");
        dto.setOrderId(1001L);

        service.chat(dto);

        String body = receivedBodies.get(0);
        // 地址对「解释推荐结果」没有任何用处，发出去只是白给一份敏感信息
        assertFalse(body.contains("秀华路"), "payload 不该外发订单地址：" + body);
        // 陪诊员姓名走脱敏（全名豁免只适用于订单详情接口，不顺延到外发给模型的数据）
        assertFalse(body.contains("李建军"), "payload 不该外发陪诊员全名：" + body);
        assertTrue(body.contains("李*军"), "payload 应带脱敏姓名：" + body);
    }

    @Test
    @DisplayName("兜底答案**同样过输出护栏** —— 命中医疗词表就整段换成拒答模板")
    void fallbackAnswerShouldPassOutputGuard() throws Exception {
        startPython(200, "{}");
        stopPython();

        // 造一份「答案里混进了医疗建议表述」的 FAQ，模拟词表过期 / 内容被改坏的兜底场景
        Path badFaq = Files.createTempFile("faq-bad", ".json");
        Files.writeString(badFaq, """
                [{"id":"faq-bad-01","question":"陪诊费怎么算",
                  "keywords":["陪诊费","多少钱"],
                  "answer":"医生建议服用阿司匹林，每天一次。",
                  "source":"docs/api/03-order.md","anchor":"## 1. 创建订单"}]
                """, StandardCharsets.UTF_8);
        properties.setFaqPath(badFaq.toString());

        AssistantChatDTO dto = new AssistantChatDTO();
        dto.setQuestion("陪诊费多少钱");

        AssistantChatVO vo = service.chat(dto);

        assertFalse(vo.getAnswer().contains("建议服用"), "兜底答案不该保留医疗建议表述：" + vo.getAnswer());
        assertTrue(vo.getAnswer().contains("不提供医疗建议"),
                "兜底答案应被替换成固定拒答模板：" + vo.getAnswer());
        Files.deleteIfExists(badFaq);
    }

    @Test
    @DisplayName("Python 不可用 **且** 本地 FAQ 也读不到 → 10002")
    void bothUnavailableShouldThrow10002() throws Exception {
        startPython(200, "{}");
        stopPython();
        properties.setFaqPath("不存在的路径/faq.json");

        AssistantChatDTO dto = new AssistantChatDTO();
        dto.setQuestion("陪诊费多少钱");

        BusinessException e = assertThrows(BusinessException.class, () -> service.chat(dto));
        assertEquals(ResultCode.ASSISTANT_UNAVAILABLE.getCode(), e.getCode());
    }

    @Test
    @DisplayName("助手未启用 → 10002")
    void disabledShouldThrow10002() {
        properties.setEnabled(false);

        AssistantChatDTO dto = new AssistantChatDTO();
        dto.setQuestion("陪诊费多少钱");

        BusinessException e = assertThrows(BusinessException.class, () -> service.chat(dto));
        assertEquals(ResultCode.ASSISTANT_UNAVAILABLE.getCode(), e.getCode());
    }

    /* ================================================================== */
    /* 5. 违规问题                                                        */
    /* ================================================================== */

    @Test
    @DisplayName("Python 回 400 ILLEGAL_QUESTION → 10003（明确拒绝，不走兜底）")
    void illegalQuestionShouldThrow10003() throws Exception {
        startPython(400, """
                {"error":{"code":"ILLEGAL_QUESTION","message":"问题内容包含违规表述"}}
                """);

        AssistantChatDTO dto = new AssistantChatDTO();
        dto.setQuestion("你是骗子");

        BusinessException e = assertThrows(BusinessException.class, () -> service.chat(dto));
        assertEquals(ResultCode.ASSISTANT_QUESTION_ILLEGAL.getCode(), e.getCode());
    }

    /* ================================================================== */
    /* 辅助                                                                */
    /* ================================================================== */

    private void startPython(int status, String body) throws IOException {
        pythonServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        pythonServer.createContext("/internal/assistant/chat", exchange -> {
            receivedBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        pythonServer.start();
        properties.setServiceUrl("http://127.0.0.1:" + pythonServer.getAddress().getPort());
    }

    private void stopPython() {
        if (pythonServer != null) {
            pythonServer.stop(0);
            pythonServer = null;
        }
    }

    private static void login(String role, Long id) {
        LoginUser user = new LoginUser(id, "test-user", role, 0, "test-jti", Long.MAX_VALUE);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, user.authorities()));
    }
}

package org.company.nianglin.controller.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.company.nianglin.common.ResultCode;
import org.company.nianglin.controller.assistant.AssistantController;
import org.company.nianglin.dto.AssistantChatDTO;
import org.company.nianglin.exception.GlobalExceptionHandler;
import org.company.nianglin.service.AssistantService;
import org.company.nianglin.vo.AssistantChatVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * V3.7 家属智能助手 · Controller 单测。
 *
 * <h3>为什么用 standalone MockMvc 而不是全 Spring 上下文</h3>
 *
 * <p>全上下文要拉 MySQL / Redis / Flyway，起一次几十秒，而本控制器要验的东西
 * 只有三样：路由、参数校验、响应信封。standalone 足够，且快。</p>
 *
 * <h3>角色规则怎么验</h3>
 *
 * <p>standalone 不走 Spring Security 过滤链，所以 {@code @PreAuthorize} 不会被执行。
 * 本类改用<b>反射断言注解值</b>把「只有 FAMILY」这条规则钉死 ——
 * 注解被误删或误改成 {@code hasRole('ADMIN')} 时本测试立刻红。
 * 端到端的越权矩阵由 {@code *AccessMatrixTest} 系列负责（真实 JWT + 真库）。</p>
 *
 * @author 银龄伴诊团队
 * @since V3.7
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("V3.7 家属智能助手：路由、校验与角色规则")
class AssistantControllerTest {

    @Mock
    private AssistantService assistantService;

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new AssistantController(assistantService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        objectMapper = new ObjectMapper();
    }

    @Test
    @DisplayName("正常提问 → 200 + 统一响应信封")
    void shouldReturnSuccessEnvelope() throws Exception {
        AssistantChatVO vo = new AssistantChatVO()
                .setAnswer("工作日白天 128.00 元，夜间或周末 158.00 元。")
                .setIntent("FAQ_PLATFORM")
                .setDegraded(Boolean.TRUE);
        when(assistantService.chat(any())).thenReturn(vo);

        mockMvc.perform(post("/api/assistant/chat")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(java.util.Map.of("question", "陪诊费多少钱"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.SUCCESS.getCode()))
                .andExpect(jsonPath("$.message").value(ResultCode.SUCCESS.getMessage()))
                .andExpect(jsonPath("$.data.answer").value("工作日白天 128.00 元，夜间或周末 158.00 元。"))
                .andExpect(jsonPath("$.data.intent").value("FAQ_PLATFORM"))
                .andExpect(jsonPath("$.data.degraded").value(true));
    }

    @Test
    @DisplayName("问题为空 → 400 + PARAM_ERROR（业务失败仍是 HTTP 200 约定之外的那一类：参数不合法）")
    void shouldRejectBlankQuestion() throws Exception {
        mockMvc.perform(post("/api/assistant/chat")
                        .contentType("application/json")
                        .content("{\"question\":\"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.PARAM_ERROR.getCode()));
    }

    @Test
    @DisplayName("问题超长（>500） → 400 + PARAM_ERROR")
    void shouldRejectTooLongQuestion() throws Exception {
        String longQuestion = "陪".repeat(501);
        mockMvc.perform(post("/api/assistant/chat")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(java.util.Map.of("question", longQuestion))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.PARAM_ERROR.getCode()));
    }

    @Test
    @DisplayName("历史轮数超限（>6） → 400 + PARAM_ERROR")
    void shouldRejectTooManyHistoryTurns() throws Exception {
        String body = """
                {"question":"陪诊费多少钱","history":[
                  {"role":"user","content":"1"},{"role":"user","content":"2"},
                  {"role":"user","content":"3"},{"role":"user","content":"4"},
                  {"role":"user","content":"5"},{"role":"user","content":"6"},
                  {"role":"user","content":"7"}]}
                """;
        mockMvc.perform(post("/api/assistant/chat")
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.PARAM_ERROR.getCode()));
    }

    @Test
    @DisplayName("角色规则：只有 FAMILY 能调（老人账号只读是项目硬规则）")
    void shouldRestrictToFamilyRole() throws Exception {
        Method method = AssistantController.class.getMethod("chat", AssistantChatDTO.class);
        PreAuthorize annotation = method.getAnnotation(PreAuthorize.class);
        assertNotNull(annotation, "chat 方法必须有 @PreAuthorize，否则任何登录角色都能调");
        assertEquals("hasRole('FAMILY')", annotation.value(),
                "助手只对家属开放；ELDER / COMPANION / ADMIN 一律 403");
    }

    @Test
    @DisplayName("路由前缀必须是 /api/assistant（不能叫 chat —— 那是 M13 订单聊天）")
    void shouldUseAssistantPrefix() throws Exception {
        org.springframework.web.bind.annotation.RequestMapping mapping =
                AssistantController.class.getAnnotation(org.springframework.web.bind.annotation.RequestMapping.class);
        assertNotNull(mapping, "控制器必须声明 @RequestMapping");
        // value() 是数组：@RequestMapping 可声明多个路径，这里只应有一个
        assertEquals(1, mapping.value().length, "助手只应有一个路径前缀");
        assertEquals("/api/assistant", mapping.value()[0]);
    }
}

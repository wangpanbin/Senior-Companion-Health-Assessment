package org.company.nianglin.security;

import org.company.nianglin.constant.RoleConstants;
import org.company.nianglin.support.TestTokens;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 体征记录的越权矩阵（V4 · P2-2）。
 *
 * <h3>三层分别防什么</h3>
 *
 * <ol>
 *   <li><b>角色门槛</b>（{@code @PreAuthorize}）：家属端点只收 FAMILY，
 *       老人端点只收 ELDER，代录端点只收 COMPANION；</li>
 *   <li><b>归属门槛</b>：家属只能读 / 写绑定老人的档案（{@code 2006}），
 *       陪诊员只能写自己服务中订单（{@code 3001 / 3004 / 3002}）；</li>
 *   <li><b>老人只读</b>：ELDER 的任何写请求由 {@code ElderReadOnlyInterceptor} 统一 403。</li>
 * </ol>
 *
 * <p>本类只发<b>不会落库</b>或<b>注定被拒</b>的请求：正向写入留给 e2e 脚本，
 * 避免单测在种子库里留下体征记录。</p>
 *
 * @author 银龄伴诊团队
 * @since V4 · P2-2
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("P2-2 体征记录越权矩阵")
class VitalSignSecurityTest {

    private static final String AUTH_HEADER = "Authorization";

    /** fam001 名下老人（张德海） */
    private static final String OWN_ELDER = "401";

    /** fam002 名下老人（李桂英） */
    private static final String OTHER_ELDER = "402";

    private static final String API_FAMILY_VITALS = "/api/family/elder/" + OWN_ELDER + "/vitals";
    private static final String API_FAMILY_TREND = "/api/family/elder/" + OWN_ELDER + "/vitals/trend";
    private static final String API_ELDER_VITALS = "/api/elder/vitals";
    private static final String API_ELDER_TREND = "/api/elder/vitals/trend";
    private static final String API_COMPANION_VITALS = "/api/execution/1001/vitals";
    /** 1015 · 陪诊员 315 的「服务中」订单 */
    private static final String API_ORDER_IN_SERVICE = "/api/execution/1015/vitals";
    /** 1025 · 陪诊员 301 的「已完成」订单 */
    private static final String API_ORDER_COMPLETED = "/api/execution/1025/vitals";
    /** 1012 · 陪诊员 312 的「已接单」订单（尚未进入服务） */
    private static final String API_ORDER_ACCEPTED = "/api/execution/1012/vitals";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private TokenStore tokenStore;

    /* ================================================================== */
    /* 1 · 角色门槛                                                        */
    /* ================================================================== */

    @ParameterizedTest(name = "家属端体征列表 · {0} → 403")
    @ValueSource(strings = {RoleConstants.ELDER, RoleConstants.COMPANION, RoleConstants.ADMIN})
    @DisplayName("家属端体征列表 · 非家属 → 403")
    void nonFamilyShouldNotReadFamilyVitals(String role) throws Exception {
        mockMvc.perform(get(API_FAMILY_VITALS).header(AUTH_HEADER, bearer(userIdOf(role), role)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @ParameterizedTest(name = "家属端体征趋势 · {0} → 403")
    @ValueSource(strings = {RoleConstants.ELDER, RoleConstants.COMPANION, RoleConstants.ADMIN})
    @DisplayName("家属端体征趋势 · 非家属 → 403")
    void nonFamilyShouldNotReadFamilyTrend(String role) throws Exception {
        mockMvc.perform(get(API_FAMILY_TREND).param("vitalType", "BLOOD_PRESSURE")
                        .header(AUTH_HEADER, bearer(userIdOf(role), role)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @ParameterizedTest(name = "老人端体征列表 · {0} → 403")
    @ValueSource(strings = {RoleConstants.FAMILY, RoleConstants.COMPANION, RoleConstants.ADMIN})
    @DisplayName("老人端体征列表 · 非老人 → 403")
    void nonElderShouldNotReadSelfVitals(String role) throws Exception {
        mockMvc.perform(get(API_ELDER_VITALS).header(AUTH_HEADER, bearer(userIdOf(role), role)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    @DisplayName("老人写体征 · ELDER 由只读拦截器统一 403")
    void elderShouldNotWriteVitals() throws Exception {
        mockMvc.perform(post(API_FAMILY_VITALS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(weightBody())
                        .header(AUTH_HEADER, bearer(201L, RoleConstants.ELDER)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @ParameterizedTest(name = "陪诊员代录 · {0} → 403")
    @ValueSource(strings = {RoleConstants.FAMILY, RoleConstants.ELDER, RoleConstants.ADMIN})
    @DisplayName("陪诊员代录 · 非陪诊员 → 403（家属不能借订单代录）")
    void nonCompanionShouldNotCreateForOrder(String role) throws Exception {
        mockMvc.perform(post(API_COMPANION_VITALS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(weightBody())
                        .header(AUTH_HEADER, bearer(userIdOf(role), role)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @ParameterizedTest(name = "家属撤回 · {0} → 403")
    @ValueSource(strings = {RoleConstants.ELDER, RoleConstants.COMPANION, RoleConstants.ADMIN})
    @DisplayName("家属撤回 · 非家属 → 403")
    void nonFamilyShouldNotWithdraw(String role) throws Exception {
        mockMvc.perform(delete("/api/family/vitals/1")
                        .header(AUTH_HEADER, bearer(userIdOf(role), role)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    /* ================================================================== */
    /* 2 · 归属门槛                                                        */
    /* ================================================================== */

    @Test
    @DisplayName("家属读别人家老人的体征 → 2006")
    void familyShouldNotReadOtherElderVitals() throws Exception {
        mockMvc.perform(get("/api/family/elder/" + OTHER_ELDER + "/vitals")
                        .header(AUTH_HEADER, bearer(101L, RoleConstants.FAMILY)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(2006));
    }

    @Test
    @DisplayName("家属给不存在的老人代录 → 2001，且不落库")
    void familyShouldGet2001ForUnknownElder() throws Exception {
        mockMvc.perform(post("/api/family/elder/999999/vitals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(weightBody())
                        .header(AUTH_HEADER, bearer(101L, RoleConstants.FAMILY)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(2001));
    }

    @Test
    @DisplayName("陪诊员给不存在的订单代录 → 3001")
    void companionShouldGet3001ForUnknownOrder() throws Exception {
        mockMvc.perform(post("/api/execution/999999/vitals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(weightBody())
                        .header(AUTH_HEADER, bearer(301L, RoleConstants.COMPANION)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(3001));
    }

    /* ------------------------------------------------------------------ */
    /* 2.1 · 陪诊员按订单查询（V4 · P2-2 收尾）                             */
    /* ------------------------------------------------------------------ */

    @ParameterizedTest(name = "按订单查询体征 · {0} → 403")
    @ValueSource(strings = {RoleConstants.FAMILY, RoleConstants.ELDER, RoleConstants.ADMIN})
    @DisplayName("按订单查询体征 · 非陪诊员 → 403")
    void nonCompanionShouldNotReadOrderVitals(String role) throws Exception {
        mockMvc.perform(get(API_ORDER_IN_SERVICE).header(AUTH_HEADER, bearer(userIdOf(role), role)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    @DisplayName("陪诊员查询非本单订单的体征 → 3004")
    void companionShouldNotReadForeignOrderVitals() throws Exception {
        // 1015 的陪诊员是 315，当前令牌是 301
        mockMvc.perform(get(API_ORDER_IN_SERVICE).header(AUTH_HEADER, bearer(301L, RoleConstants.COMPANION)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(3004));
    }

    @Test
    @DisplayName("陪诊员查询不存在的订单 → 3001")
    void companionShouldGet3001ForUnknownOrderQuery() throws Exception {
        mockMvc.perform(get("/api/execution/999999/vitals")
                        .header(AUTH_HEADER, bearer(301L, RoleConstants.COMPANION)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(3001));
    }

    @Test
    @DisplayName("陪诊员查询「已接单」订单的体征 → 3002")
    void companionShouldGet3002ForAcceptedOrder() throws Exception {
        mockMvc.perform(get(API_ORDER_ACCEPTED).header(AUTH_HEADER, bearer(312L, RoleConstants.COMPANION)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(3002));
    }

    @Test
    @DisplayName("陪诊员查询本单「服务中」订单的体征 → 200")
    void companionShouldReadInServiceOrderVitals() throws Exception {
        mockMvc.perform(get(API_ORDER_IN_SERVICE).header(AUTH_HEADER, bearer(315L, RoleConstants.COMPANION)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.records").isArray());
    }

    @Test
    @DisplayName("陪诊员查询本单「已完成」订单的体征 → 200（服务结束后仍可回看）")
    void companionShouldReadCompletedOrderVitals() throws Exception {
        mockMvc.perform(get(API_ORDER_COMPLETED).header(AUTH_HEADER, bearer(301L, RoleConstants.COMPANION)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.records").isArray());
    }

    /* ================================================================== */
    /* 3 · 正面用例                                                        */
    /* ================================================================== */

    @Test
    @DisplayName("家属读自家老人的体征列表 → 200，records 是数组")
    void familyShouldReadOwnElderVitals() throws Exception {
        mockMvc.perform(get(API_FAMILY_VITALS).header(AUTH_HEADER, bearer(101L, RoleConstants.FAMILY)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.records").isArray());
    }

    @Test
    @DisplayName("家属读自家老人的体征趋势 → 200，points 是数组")
    void familyShouldReadOwnElderTrend() throws Exception {
        mockMvc.perform(get(API_FAMILY_TREND).param("vitalType", "BLOOD_PRESSURE")
                        .header(AUTH_HEADER, bearer(101L, RoleConstants.FAMILY)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.points").isArray())
                .andExpect(jsonPath("$.data.truncated").value(false));
    }

    @Test
    @DisplayName("老人读本人体征与趋势 → 200")
    void elderShouldReadOwnVitals() throws Exception {
        mockMvc.perform(get(API_ELDER_VITALS).header(AUTH_HEADER, bearer(201L, RoleConstants.ELDER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.records").isArray());

        mockMvc.perform(get(API_ELDER_TREND).param("vitalType", "WEIGHT")
                        .header(AUTH_HEADER, bearer(201L, RoleConstants.ELDER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    /* ================================================================== */
    /* 4 · 未登录                                                          */
    /* ================================================================== */

    @Test
    @DisplayName("未登录 · 家属端体征列表 → 401")
    void anonymousShouldGet401() throws Exception {
        mockMvc.perform(get(API_FAMILY_VITALS))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    /* ================================================================== */
    /* 工具                                                                */
    /* ================================================================== */

    /** 合法体重请求体；时间取固定过去日期，避开「未来 5 分钟」容差 */
    private static String weightBody() {
        return """
                {"vitalType":"WEIGHT","measuredAt":"2026-10-05 08:10:00","weightKg":62.5}
                """;
    }

    private long userIdOf(String role) {
        return switch (role) {
            case RoleConstants.ELDER -> 201L;
            case RoleConstants.FAMILY -> 101L;
            case RoleConstants.COMPANION -> 301L;
            default -> 1L;
        };
    }

    private String bearer(long userId, String role) {
        return TestTokens.bearer(tokenProvider, tokenStore, userId, role, "v4.2");
    }
}

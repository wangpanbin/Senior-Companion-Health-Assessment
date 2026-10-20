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
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * V3.5 平台运营驾驶舱的越权矩阵：4 角色 × 4 端点。
 *
 * <p>驾驶舱能一次性看到全平台的订单量、投诉率、评分与陪诊员表现，
 * 与 {@code StatisticsAccessMatrixTest}（M10 统计看板）同构：
 * <b>非 ADMIN 一律 403，未登录一律 401</b>。</p>
 *
 * <p>四个端点逐个覆盖，而不是只测一个 —— 类级 {@code @PreAuthorize} 写对了，
 * 但只要有人给某个方法加了 {@code @PreAuthorize("permitAll")}，
 * 漏掉的那个端点就是一个数据泄露口，而漏写不会编译报错。</p>
 *
 * @author 银龄伴诊团队
 * @since V3.5
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("V3.5 运营驾驶舱越权矩阵：4 角色 × 4 端点")
class OperationsAccessMatrixTest {

    private static final String AUTH_HEADER = "Authorization";
    private static final long ADMIN_ID = 1L;

    /** 概览 */
    private static final String API_OVERVIEW = "/api/admin/operations/overview";
    /** 异常中心 */
    private static final String API_EXCEPTIONS = "/api/admin/operations/exceptions";
    /** 趋势 */
    private static final String API_TREND = "/api/admin/operations/trend";
    /** 健康度 */
    private static final String API_HEALTH = "/api/admin/operations/health";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private TokenStore tokenStore;

    /* ================================================================== */
    /* 1 · 概览                                                            */
    /* ================================================================== */

    @ParameterizedTest(name = "概览 · {0} → 403")
    @ValueSource(strings = {RoleConstants.ELDER, RoleConstants.FAMILY, RoleConstants.COMPANION})
    @DisplayName("概览 · 非管理员看驾驶舱概览 → 403")
    void nonAdminShouldNotReadOverview(String role) throws Exception {
        mockMvc.perform(get(API_OVERVIEW).header(AUTH_HEADER, bearer(userIdOf(role), role)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    @DisplayName("概览 · 未登录 → 401")
    void anonymousShouldGet401OnOverview() throws Exception {
        mockMvc.perform(get(API_OVERVIEW))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    /* ================================================================== */
    /* 2 · 异常中心                                                        */
    /* ================================================================== */

    @ParameterizedTest(name = "异常中心 · {0} → 403")
    @ValueSource(strings = {RoleConstants.ELDER, RoleConstants.FAMILY, RoleConstants.COMPANION})
    @DisplayName("异常中心 · 非管理员看平台异常 → 403")
    void nonAdminShouldNotReadExceptions(String role) throws Exception {
        mockMvc.perform(get(API_EXCEPTIONS).header(AUTH_HEADER, bearer(userIdOf(role), role)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    @DisplayName("异常中心 · 未登录 → 401")
    void anonymousShouldGet401OnExceptions() throws Exception {
        mockMvc.perform(get(API_EXCEPTIONS))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    /* ================================================================== */
    /* 3 · 趋势                                                            */
    /* ================================================================== */

    @ParameterizedTest(name = "趋势 · {0} → 403")
    @ValueSource(strings = {RoleConstants.ELDER, RoleConstants.FAMILY, RoleConstants.COMPANION})
    @DisplayName("趋势 · 非管理员看平台趋势 → 403")
    void nonAdminShouldNotReadTrend(String role) throws Exception {
        mockMvc.perform(get(API_TREND).header(AUTH_HEADER, bearer(userIdOf(role), role)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    @DisplayName("趋势 · 未登录 → 401")
    void anonymousShouldGet401OnTrend() throws Exception {
        mockMvc.perform(get(API_TREND))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    /* ================================================================== */
    /* 4 · 健康度                                                          */
    /* ================================================================== */

    @ParameterizedTest(name = "健康度 · {0} → 403")
    @ValueSource(strings = {RoleConstants.ELDER, RoleConstants.FAMILY, RoleConstants.COMPANION})
    @DisplayName("健康度 · 非管理员看平台健康度 → 403")
    void nonAdminShouldNotReadHealth(String role) throws Exception {
        mockMvc.perform(get(API_HEALTH).header(AUTH_HEADER, bearer(userIdOf(role), role)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    @DisplayName("健康度 · 未登录 → 401")
    void anonymousShouldGet401OnHealth() throws Exception {
        mockMvc.perform(get(API_HEALTH))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    /* ================================================================== */
    /* 5 · 管理员侧：注解确实放行                                          */
    /* ================================================================== */

    @Test
    @DisplayName("正面用例 · 管理员看概览 → 非 403/401")
    void adminShouldPassAnnotationGate() throws Exception {
        mockMvc.perform(get(API_OVERVIEW).header(AUTH_HEADER, admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(not(anyOf(is(403), is(401)))));
    }

    @Test
    @DisplayName("正面用例 · 管理员看异常中心 → 非 403/401")
    void adminShouldPassAnnotationGateOnExceptions() throws Exception {
        mockMvc.perform(get(API_EXCEPTIONS).header(AUTH_HEADER, admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(not(anyOf(is(403), is(401)))));
    }

    /* ================================================================== */
    /* 工具                                                                */
    /* ================================================================== */

    private String admin() {
        return bearer(ADMIN_ID, RoleConstants.ADMIN);
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
        return TestTokens.bearer(tokenProvider, tokenStore, userId, role, "v35");
    }
}

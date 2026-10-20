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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 老人健康协同的越权矩阵（V3.2 · FR-Overview / FR-Timeline）。
 *
 * <h3>这一组要防的是什么</h3>
 *
 * <p>健康数据是本项目里最敏感的一类：就医记录、用药记录、漏服情况。
 * 一个家属能读到<b>别人家</b>老人的健康数据，比读错一条订单详情严重得多 ——
 * 它同时泄露了疾病史、用药史与就诊时间。</p>
 *
 * <p>所以这里对每个端点都问三件事：</p>
 * <ol>
 *   <li>非本角色 → 403（注解层）；</li>
 *   <li>本角色但不是自己的数据 → 2006（归属层）；</li>
 *   <li>未登录 → 401（认证层）。</li>
 * </ol>
 *
 * <p>老人端接口<b>不接收 elderId</b>，所以它没有「参数改一下就能看别人」的面 ——
 * 这一点由路由形状保证，本组顺带断言它只对 ELDER 开放。</p>
 *
 * @author 银龄伴诊团队
 * @since V3.2
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("V3.2 老人健康协同越权矩阵：4 角色 × 3 类接口")
class ElderHealthAccessTest {

    private static final String AUTH_HEADER = "Authorization";

    /** fam001 名下的老人（张德海） */
    private static final String OWN_ELDER = "401";

    /** fam002 名下的老人（李桂英） */
    private static final String OTHER_ELDER = "402";

    private static final String API_SUMMARY = "/api/family/health/summary";
    private static final String API_OVERVIEW = "/api/family/elder/" + OWN_ELDER + "/health/overview";
    private static final String API_TIMELINE = "/api/family/elder/" + OWN_ELDER + "/health/timeline";
    private static final String API_SELF_OVERVIEW = "/api/elder/health/overview";
    private static final String API_SELF_TIMELINE = "/api/elder/health/timeline";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private TokenStore tokenStore;

    /* ================================================================== */
    /* 1 · 角色门槛                                                        */
    /* ================================================================== */

    @ParameterizedTest(name = "家属端概览 · {0} → 403")
    @ValueSource(strings = {RoleConstants.ELDER, RoleConstants.COMPANION, RoleConstants.ADMIN})
    @DisplayName("家属端概览 · 非家属角色 → 403")
    void nonFamilyShouldNotReadFamilyOverview(String role) throws Exception {
        mockMvc.perform(get(API_OVERVIEW).header(AUTH_HEADER, bearer(userIdOf(role), role)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @ParameterizedTest(name = "家属端时间线 · {0} → 403")
    @ValueSource(strings = {RoleConstants.ELDER, RoleConstants.COMPANION, RoleConstants.ADMIN})
    @DisplayName("家属端时间线 · 非家属角色 → 403")
    void nonFamilyShouldNotReadFamilyTimeline(String role) throws Exception {
        mockMvc.perform(get(API_TIMELINE).header(AUTH_HEADER, bearer(userIdOf(role), role)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @ParameterizedTest(name = "家属端首页摘要 · {0} → 403")
    @ValueSource(strings = {RoleConstants.ELDER, RoleConstants.COMPANION, RoleConstants.ADMIN})
    @DisplayName("家属端首页摘要 · 非家属角色 → 403")
    void nonFamilyShouldNotReadFamilySummary(String role) throws Exception {
        mockMvc.perform(get(API_SUMMARY).header(AUTH_HEADER, bearer(userIdOf(role), role)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @ParameterizedTest(name = "老人端概览 · {0} → 403")
    @ValueSource(strings = {RoleConstants.FAMILY, RoleConstants.COMPANION, RoleConstants.ADMIN})
    @DisplayName("老人端概览 · 非老人角色 → 403（老人端只服务老人本人）")
    void nonElderShouldNotReadSelfOverview(String role) throws Exception {
        mockMvc.perform(get(API_SELF_OVERVIEW).header(AUTH_HEADER, bearer(userIdOf(role), role)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @ParameterizedTest(name = "老人端时间线 · {0} → 403")
    @ValueSource(strings = {RoleConstants.FAMILY, RoleConstants.COMPANION, RoleConstants.ADMIN})
    @DisplayName("老人端时间线 · 非老人角色 → 403")
    void nonElderShouldNotReadSelfTimeline(String role) throws Exception {
        mockMvc.perform(get(API_SELF_TIMELINE).header(AUTH_HEADER, bearer(userIdOf(role), role)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    /* ================================================================== */
    /* 2 · 归属校验：家属不得读别人家的老人                                  */
    /* ================================================================== */

    @Test
    @DisplayName("家属端概览 · 读别人家绑定的老人 → 2006")
    void familyShouldNotReadOtherFamilyElder() throws Exception {
        mockMvc.perform(get("/api/family/elder/" + OTHER_ELDER + "/health/overview")
                        .header(AUTH_HEADER, bearer(101L, RoleConstants.FAMILY)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(2006));
    }

    @Test
    @DisplayName("家属端时间线 · 读别人家绑定的老人 → 2006")
    void familyShouldNotReadOtherFamilyElderTimeline() throws Exception {
        mockMvc.perform(get("/api/family/elder/" + OTHER_ELDER + "/health/timeline")
                        .header(AUTH_HEADER, bearer(101L, RoleConstants.FAMILY)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(2006));
    }

    @Test
    @DisplayName("家属端概览 · 老人档案不存在 → 2001")
    void familyShouldGetNotFoundForUnknownElder() throws Exception {
        mockMvc.perform(get("/api/family/elder/999999/health/overview")
                        .header(AUTH_HEADER, bearer(101L, RoleConstants.FAMILY)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(2001));
    }

    /* ================================================================== */
    /* 3 · 正面用例                                                        */
    /* ================================================================== */

    @Test
    @DisplayName("正面用例 · 家属读自家老人的概览 → 200")
    void familyShouldReadOwnElder() throws Exception {
        mockMvc.perform(get(API_OVERVIEW).header(AUTH_HEADER, bearer(101L, RoleConstants.FAMILY)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.medication.completionRate").exists());
    }

    @Test
    @DisplayName("正面用例 · 家属读自家老人的时间线 → 200，items 是数组")
    void familyShouldReadOwnElderTimeline() throws Exception {
        mockMvc.perform(get(API_TIMELINE).header(AUTH_HEADER, bearer(101L, RoleConstants.FAMILY)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.items").isArray())
                .andExpect(jsonPath("$.data.hasMore").exists());
    }

    @Test
    @DisplayName("正面用例 · 老人读自己的概览 → 200")
    void elderShouldReadOwnOverview() throws Exception {
        mockMvc.perform(get(API_SELF_OVERVIEW).header(AUTH_HEADER, bearer(201L, RoleConstants.ELDER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    @DisplayName("正面用例 · 老人读自己的时间线 → 200")
    void elderShouldReadOwnTimeline() throws Exception {
        mockMvc.perform(get(API_SELF_TIMELINE).header(AUTH_HEADER, bearer(201L, RoleConstants.ELDER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    /* ================================================================== */
    /* 4 · 未登录                                                          */
    /* ================================================================== */

    @Test
    @DisplayName("未登录 · 家属端概览 → 401")
    void anonymousShouldGet401OnOverview() throws Exception {
        mockMvc.perform(get(API_OVERVIEW))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    @DisplayName("未登录 · 老人端时间线 → 401")
    void anonymousShouldGet401OnSelfTimeline() throws Exception {
        mockMvc.perform(get(API_SELF_TIMELINE))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    /* ================================================================== */
    /* 5 · 参数校验                                                        */
    /* ================================================================== */

    @Test
    @DisplayName("非法事件分类 → 400（不静默降级）")
    void illegalCategoriesShouldBeRejected() throws Exception {
        mockMvc.perform(get(API_TIMELINE).param("categories", "NOT_A_CATEGORY")
                        .header(AUTH_HEADER, bearer(101L, RoleConstants.FAMILY)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @DisplayName("区间超过 366 天 → 9002")
    void tooWideRangeShouldBeRejected() throws Exception {
        mockMvc.perform(get(API_OVERVIEW).param("startDate", "2020-01-01")
                        .param("endDate", "2026-10-01")
                        .header(AUTH_HEADER, bearer(101L, RoleConstants.FAMILY)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(9002));
    }

    /* ================================================================== */
    /* 工具                                                                */
    /* ================================================================== */

    private long userIdOf(String role) {
        return switch (role) {
            case RoleConstants.ELDER -> 201L;
            case RoleConstants.FAMILY -> 101L;
            case RoleConstants.COMPANION -> 301L;
            default -> 1L;
        };
    }

    private String bearer(long userId, String role) {
        return TestTokens.bearer(tokenProvider, tokenStore, userId, role, "v3.2");
    }
}

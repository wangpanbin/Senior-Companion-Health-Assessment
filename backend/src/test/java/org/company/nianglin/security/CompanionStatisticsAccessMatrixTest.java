package org.company.nianglin.security;

import org.company.nianglin.constant.RoleConstants;
import org.company.nianglin.support.TestTokens;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
 * 陪诊员工作台的越权矩阵（V3.3 · T3）。
 *
 * <h3>这个矩阵在防什么</h3>
 *
 * <p>工作台接口返回的是<b>某个陪诊员的经营数据</b>（收入、完成率、准时率）。
 * 一旦端点没锁角色，同行就能看到对方的收入 —— 这是隐私红线级别的泄露，
 * 而且<b>不会报错</b>，只会安静地返回一个 200。</p>
 *
 * <h3>为什么单独一个 Controller 类</h3>
 *
 * <p>同包的 {@code StatisticsController} 是类级 ADMIN 注解。工作台端点如果挂在它下面，
 * 就得靠方法级注解去覆盖类级注解 —— 「靠例外活着的规则」漏一个就是一次泄露。
 * 所以另起一个类，类级注解与端点集合一一对应。</p>
 *
 * <h3>「不接收 companionId 入参」也要测</h3>
 *
 * <p>本组用例专门验证：请求里塞 {@code ?companionId=别人的ID} 也没用 ——
 * 端点压根不读它，返回的永远是当前登录者自己的数据。这条比注解更硬：
 * 注解靠人加，参数靠「压根没有」。</p>
 *
 * @author 银龄伴诊团队
 * @since V3.3
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("V3.3 陪诊员工作台越权矩阵：4 角色 × 4 端点")
class CompanionStatisticsAccessMatrixTest {

    private static final String AUTH_HEADER = "Authorization";

    /** 端点清单：概览 / 趋势 / 画像 / 收入 */
    private static final String API_OVERVIEW = "/api/statistics/companion/me";
    private static final String API_TREND = "/api/statistics/companion/me/trend";
    private static final String API_PROFILE = "/api/statistics/companion/me/profile";
    private static final String API_INCOME = "/api/statistics/companion/me/income";

    /** 种子里的两个陪诊员账号（comp001 / comp002） */
    private static final long COMPANION_A = 301L;
    private static final long COMPANION_B = 302L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private TokenStore tokenStore;

    /* ================================================================== */
    /* 1 · 非陪诊员角色一律 403                                               */
    /* ================================================================== */

    @ParameterizedTest(name = "{1} · {0} → 403")
    @CsvSource({
            "ELDER,  概览",
            "FAMILY, 概览",
            "ADMIN,  概览"
    })
    @DisplayName("概览 · 非陪诊员看别人的经营数据 → 403")
    void nonCompanionShouldNotReadOverview(String role, String label) throws Exception {
        mockMvc.perform(get(API_OVERVIEW).header(AUTH_HEADER, bearer(userIdOf(role), role)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @ParameterizedTest(name = "趋势 · {0} → 403")
    @ValueSource(strings = {RoleConstants.ELDER, RoleConstants.FAMILY, RoleConstants.ADMIN})
    @DisplayName("趋势 · 非陪诊员 → 403")
    void nonCompanionShouldNotReadTrend(String role) throws Exception {
        mockMvc.perform(get(API_TREND).header(AUTH_HEADER, bearer(userIdOf(role), role)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @ParameterizedTest(name = "画像 · {0} → 403")
    @ValueSource(strings = {RoleConstants.ELDER, RoleConstants.FAMILY, RoleConstants.ADMIN})
    @DisplayName("画像 · 非陪诊员 → 403")
    void nonCompanionShouldNotReadProfile(String role) throws Exception {
        mockMvc.perform(get(API_PROFILE).header(AUTH_HEADER, bearer(userIdOf(role), role)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @ParameterizedTest(name = "收入 · {0} → 403")
    @ValueSource(strings = {RoleConstants.ELDER, RoleConstants.FAMILY, RoleConstants.ADMIN})
    @DisplayName("收入 · 非陪诊员 → 403（收入是最敏感的一类数据）")
    void nonCompanionShouldNotReadIncome(String role) throws Exception {
        mockMvc.perform(get(API_INCOME).header(AUTH_HEADER, bearer(userIdOf(role), role)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    @DisplayName("未登录 · 四个端点一律 401")
    void anonymousShouldGet401() throws Exception {
        mockMvc.perform(get(API_OVERVIEW)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(API_TREND)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(API_PROFILE)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(API_INCOME)).andExpect(status().isUnauthorized());
    }

    /* ================================================================== */
    /* 2 · 陪诊员本人：四个端点都放行                                        */
    /* ================================================================== */

    @Test
    @DisplayName("正面用例 · 陪诊员看自己的工作台 → 非 403/401")
    void companionShouldPassAnnotationGate() throws Exception {
        mockMvc.perform(get(API_OVERVIEW).header(AUTH_HEADER, bearer(COMPANION_A, RoleConstants.COMPANION)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(not(anyOf(is(403), is(401)))))
                .andExpect(jsonPath("$.data.periodStart").isNotEmpty())
                .andExpect(jsonPath("$.data.completedOrderCount").isNumber())
                .andExpect(jsonPath("$.data.completionRate").isString())
                .andExpect(jsonPath("$.data.periodIncome").isString());

        mockMvc.perform(get(API_TREND).header(AUTH_HEADER, bearer(COMPANION_A, RoleConstants.COMPANION)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.dimension").value("ORDER"))
                .andExpect(jsonPath("$.data.categories").isArray())
                .andExpect(jsonPath("$.data.series[0].data").isArray());

        mockMvc.perform(get(API_PROFILE).header(AUTH_HEADER, bearer(COMPANION_A, RoleConstants.COMPANION)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalCompletionRate").isString())
                .andExpect(jsonPath("$.data.punctualityRate").isString())
                .andExpect(jsonPath("$.data.expertise").isArray());

        mockMvc.perform(get(API_INCOME).header(AUTH_HEADER, bearer(COMPANION_A, RoleConstants.COMPANION)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.actualIncome").isString())
                .andExpect(jsonPath("$.data.unsettledAmount").isString())
                .andExpect(jsonPath("$.data.settledAmount").isString());
    }

    /* ================================================================== */
    /* 3 · 不接收 companionId 入参：塞别人的 ID 也拿不到别人的数据              */
    /* ================================================================== */

    @Test
    @DisplayName("越权尝试 · ?companionId=别人 → 数据不变（端点压根不读它）")
    void companionIdParameterShouldBeIgnored() throws Exception {
        String plain = mockMvc.perform(get(API_OVERVIEW)
                        .header(AUTH_HEADER, bearer(COMPANION_A, RoleConstants.COMPANION)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String injected = mockMvc.perform(get(API_OVERVIEW)
                        .param("companionId", String.valueOf(COMPANION_B))
                        .header(AUTH_HEADER, bearer(COMPANION_A, RoleConstants.COMPANION)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // 两个陪诊员在种子里有不同的累计完成单数，注入参数后数字不该变
        assertEqualsJson(plain, injected);
    }

    @Test
    @DisplayName("越权尝试 · 两个陪诊员互相看不到对方的画像")
    void companionsShouldNotSeeEachOther() throws Exception {
        String a = mockMvc.perform(get(API_PROFILE)
                        .header(AUTH_HEADER, bearer(COMPANION_A, RoleConstants.COMPANION)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String b = mockMvc.perform(get(API_PROFILE)
                        .header(AUTH_HEADER, bearer(COMPANION_B, RoleConstants.COMPANION)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertNotEqualsJson(a, b);
    }

    /* ================================================================== */
    /* 工具                                                                */
    /* ================================================================== */

    /** 两段 JSON 必须完全一致 */
    private static void assertEqualsJson(String expected, String actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError("注入 companionId 后返回的数据发生了变化：\n期望：" + expected + "\n实际：" + actual);
        }
    }

    /** 两段 JSON 必须不同 */
    private static void assertNotEqualsJson(String a, String b) {
        if (a.equals(b)) {
            throw new AssertionError("两个陪诊员拿到的画像完全相同，说明身份隔离没生效：\nA：" + a + "\nB：" + b);
        }
    }

    private long userIdOf(String role) {
        return switch (role) {
            case RoleConstants.ELDER -> 201L;
            case RoleConstants.FAMILY -> 101L;
            case RoleConstants.COMPANION -> COMPANION_A;
            default -> 1L;
        };
    }

    private String bearer(long userId, String role) {
        return TestTokens.bearer(tokenProvider, tokenStore, userId, role, "v33");
    }
}

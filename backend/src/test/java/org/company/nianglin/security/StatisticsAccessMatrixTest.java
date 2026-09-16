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
 * M10 数据统计与导出越权矩阵：4 角色 × 3 类接口（总览 / 明细 / 导出）。
 *
 * <p>M10 与 M9 同构：{@code StatisticsController} 整类 `@PreAuthorize("hasRole('ADMIN')")`。
 * 但导出接口值得单独盯 —— 一次越权导出就是<b>整表数据外泄</b>，
 * 与「读一个看板数字」完全不是一个量级。所以本矩阵对 `/export/*` 单独覆盖。</p>
 *
 * <p>导出接口未登录时会在鉴权层被拒（401），不会真的生成文件；
 * 管理员侧不对导出做正面断言（会写响应流），只验证注解在非管理员侧确实拦得住。</p>
 *
 * @author 银龄伴诊团队
 * @since M10
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("M10 统计与导出越权矩阵：4 角色 × 3 类接口")
class StatisticsAccessMatrixTest {

    private static final String AUTH_HEADER = "Authorization";
    private static final long ADMIN_ID = 1L;

    /** 看板总览 */
    private static final String API_OVERVIEW = "/api/statistics/overview";
    /** 陪诊员排行（明细类） */
    private static final String API_RANK = "/api/statistics/companion-rank";
    /** 订单导出（最敏感：整表外泄风险） */
    private static final String API_EXPORT_ORDER = "/api/statistics/export/order";
    /** 用户导出（含 PII，最敏感） */
    private static final String API_EXPORT_USER = "/api/statistics/export/user";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private TokenStore tokenStore;

    /* ================================================================== */
    /* 1 · 总览：非管理员一律 403                                            */
    /* ================================================================== */

    @ParameterizedTest(name = "总览 · {0} → 403")
    @ValueSource(strings = {RoleConstants.ELDER, RoleConstants.FAMILY, RoleConstants.COMPANION})
    @DisplayName("总览 · 非管理员看数据看板 → 403")
    void nonAdminShouldNotReadOverview(String role) throws Exception {
        mockMvc.perform(get(API_OVERVIEW).header(AUTH_HEADER, bearer(userIdOf(role), role)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    @DisplayName("总览 · 未登录 → 401")
    void anonymousShouldGet401OnOverview() throws Exception {
        mockMvc.perform(get(API_OVERVIEW))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    /* ================================================================== */
    /* 2 · 明细：非管理员一律 403                                            */
    /* ================================================================== */

    @ParameterizedTest(name = "排行 · {0} → 403")
    @ValueSource(strings = {RoleConstants.ELDER, RoleConstants.FAMILY, RoleConstants.COMPANION})
    @DisplayName("明细 · 非管理员看陪诊员排行 → 403")
    void nonAdminShouldNotReadRank(String role) throws Exception {
        mockMvc.perform(get(API_RANK).header(AUTH_HEADER, bearer(userIdOf(role), role)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    /* ================================================================== */
    /* 3 · 导出：非管理员一律 403（本类最重要的一组）                          */
    /* ================================================================== */

    @ParameterizedTest(name = "导出订单 · {0} → 403")
    @ValueSource(strings = {RoleConstants.ELDER, RoleConstants.FAMILY, RoleConstants.COMPANION})
    @DisplayName("导出 · 非管理员导出订单表 → 403（越权导出即整表外泄）")
    void nonAdminShouldNotExportOrders(String role) throws Exception {
        mockMvc.perform(get(API_EXPORT_ORDER).header(AUTH_HEADER, bearer(userIdOf(role), role)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @ParameterizedTest(name = "导出用户 · {0} → 403")
    @ValueSource(strings = {RoleConstants.ELDER, RoleConstants.FAMILY, RoleConstants.COMPANION})
    @DisplayName("导出 · 非管理员导出用户表 → 403（含 PII，尤其不能外泄）")
    void nonAdminShouldNotExportUsers(String role) throws Exception {
        mockMvc.perform(get(API_EXPORT_USER).header(AUTH_HEADER, bearer(userIdOf(role), role)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    @DisplayName("导出 · 未登录导出订单 → 401")
    void anonymousShouldGet401OnExportOrder() throws Exception {
        mockMvc.perform(get(API_EXPORT_ORDER))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    @DisplayName("导出 · 未登录导出用户 → 401（未认证也不该触发任何查询）")
    void anonymousShouldGet401OnExportUser() throws Exception {
        mockMvc.perform(get(API_EXPORT_USER))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    /* ================================================================== */
    /* 4 · 管理员侧：注解确实放行                                            */
    /* ================================================================== */

    @Test
    @DisplayName("正面用例 · 管理员看总览 → 非 403/401")
    void adminShouldPassAnnotationGate() throws Exception {
        mockMvc.perform(get(API_OVERVIEW).header(AUTH_HEADER, admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(not(anyOf(is(403), is(401)))));
    }

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
        return TestTokens.bearer(tokenProvider, tokenStore, userId, role, "m10");
    }
}

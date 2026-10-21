package org.company.nianglin.service;

import org.company.nianglin.constant.RoleConstants;
import org.company.nianglin.security.JwtTokenProvider;
import org.company.nianglin.security.TokenStore;
import org.company.nianglin.support.TestTokens;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 指标字典只读导出的<b>端点契约与越权</b>校验（V3.6 · T7 · FR-Trace）。
 *
 * <h3>为什么要单独一层</h3>
 *
 * <p>导出器单测锁的是「转换对了」，本类锁的是两件别的事：</p>
 * <ul>
 *   <li><b>前端拿到什么形状</b>：字段一改名，前端就是一片空白 + 一句「接口没数据」；</li>
 *   <li><b>谁能看</b>：字典里写着全平台的统计口径（订单量、履约率、投诉率、收入），
 *       漏一次鉴权就是一个数据泄露口 —— 而「漏写注解」不会报错、不会被编译拦下。</li>
 * </ul>
 *
 * <p>契约来源：{@code docs/spec/V3.6-data-loop-spec.md} §二
 * （{@code GET /api/admin/metrics} 指标字典的只读导出）。</p>
 *
 * @author 银龄伴诊团队
 * @since V3.6
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("V3.6 指标字典导出端点：响应契约 + 越权")
class MetricDictionaryEndpointTest {

    private static final String AUTH_HEADER = "Authorization";
    private static final long ADMIN_ID = 1L;
    private static final String API_METRICS = "/api/admin/metrics";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private TokenStore tokenStore;

    /* ================================================================== */
    /* 1 · 响应契约                                                        */
    /* ================================================================== */

    @Test
    @DisplayName("管理员导出 → 版本 / 条数 / 指标列表齐全，每条带全五要素")
    void adminShouldExportMetricDictionary() throws Exception {
        mockMvc.perform(get(API_METRICS).header(AUTH_HEADER, admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.version").isNumber())
                .andExpect(jsonPath("$.data.total").isNumber())
                .andExpect(jsonPath("$.data.metrics", not(hasSize(0))))
                // 第一条的五要素（口径的完整性就是这五项，决策 6.1）
                .andExpect(jsonPath("$.data.metrics[0].key").isNotEmpty())
                .andExpect(jsonPath("$.data.metrics[0].name").isNotEmpty())
                .andExpect(jsonPath("$.data.metrics[0].scope").isNotEmpty())
                .andExpect(jsonPath("$.data.metrics[0].period").isNotEmpty())
                .andExpect(jsonPath("$.data.metrics[0].source").isNotEmpty())
                .andExpect(jsonPath("$.data.metrics[0].formula").isNotEmpty())
                .andExpect(jsonPath("$.data.metrics[0].caliberVersion").isNumber())
                .andExpect(jsonPath("$.data.metrics[0].endpoints").isArray());
    }

    @Test
    @DisplayName("导出的是口径而不是指标值：响应里不出现任何统计值字段")
    void exportShouldNotContainMetricValues() throws Exception {
        // 本端点只导出「定义」—— 一旦有人把统计值塞进来，
        // 它就该进 metrics/endpoints.yml（那里登记的是「端点返回哪些指标值」），
        // 否则「端点 ↔ 指标」机器面就漏了一个出口。
        mockMvc.perform(get(API_METRICS).header(AUTH_HEADER, admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.metrics[0].fulfillmentRate").doesNotExist())
                .andExpect(jsonPath("$.data.metrics[0].value").doesNotExist())
                .andExpect(jsonPath("$.data.metrics[0].formula",
                        not(emptyOrNullString())));
    }

    /* ================================================================== */
    /* 2 · 越权                                                            */
    /* ================================================================== */

    @Test
    @DisplayName("非管理员导出指标字典 → 403（字典含全平台口径）")
    void nonAdminShouldNotExportMetricDictionary() throws Exception {
        mockMvc.perform(get(API_METRICS)
                        .header(AUTH_HEADER, TestTokens.bearer(
                                tokenProvider, tokenStore, 101L, RoleConstants.FAMILY, "m9")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    @DisplayName("未登录导出指标字典 → 401")
    void anonymousShouldGet401OnMetricDictionary() throws Exception {
        mockMvc.perform(get(API_METRICS))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    /* ================================================================== */

    private String admin() {
        return TestTokens.bearer(tokenProvider, tokenStore, ADMIN_ID, RoleConstants.ADMIN, "m9");
    }
}

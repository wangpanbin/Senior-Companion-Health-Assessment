package org.company.nianglin.service;

import org.company.nianglin.security.JwtTokenProvider;
import org.company.nianglin.security.TokenStore;
import org.company.nianglin.support.TestTokens;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * V3.6 两个新端点的<b>响应契约</b>校验（V3.6 · T4 / T5 · T7）。
 *
 * <h3>为什么要有这一层</h3>
 *
 * <p>单测锁的是「Service 算出来的数对不对」，越权矩阵锁的是「谁能看」。
 * <b>前端拿到什么形状</b>这两件事都不管 —— 而字段一改名，前端就是一片空白 +
 * 一句「接口没数据」。所以这里把 JSON 的<b>字段名与类型</b>钉死。</p>
 *
 * <p>契约来源：{@code docs/spec/V3.6-tickets.md} §五（缺口表）与 §六（效果对比）。</p>
 *
 * @author 银龄伴诊团队
 * @since V3.6
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("V3.6 新端点响应契约：字段名 / 类型 / 桶数")
class OperationsEndpointContractTest {

    private static final String AUTH_HEADER = "Authorization";
    private static final long ADMIN_ID = 1L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private TokenStore tokenStore;

    /* ================================================================== */
    /* 1 · 供给缺口                                                        */
    /* ================================================================== */

    @Test
    @DisplayName("供给缺口 · 响应结构与 tickets §五 一致（168 个桶，空桶也返回）")
    void gapResponseShouldMatchContract() throws Exception {
        mockMvc.perform(get("/api/admin/operations/gap")
                        .header(AUTH_HEADER, admin())
                        .param("range", "CUSTOM")
                        .param("startDate", "2026-08-01")
                        .param("endDate", "2026-09-30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.periodStart").value("2026-08-01"))
                .andExpect(jsonPath("$.data.periodEnd").value("2026-09-30"))
                .andExpect(jsonPath("$.data.total").value(168))
                .andExpect(jsonPath("$.data.companionsWithoutSlots").isNumber())
                .andExpect(jsonPath("$.data.items", hasSize(168)))
                // 第一格：周一 0 点；最后一格：周日 23 点
                .andExpect(jsonPath("$.data.items[0].weekday").value(1))
                .andExpect(jsonPath("$.data.items[0].hour").value(0))
                .andExpect(jsonPath("$.data.items[0].pendingUnaccepted").isNumber())
                .andExpect(jsonPath("$.data.items[0].availableCompanions").isNumber())
                .andExpect(jsonPath("$.data.items[0].gap").isNumber())
                .andExpect(jsonPath("$.data.items[167].weekday").value(7))
                .andExpect(jsonPath("$.data.items[167].hour").value(23));
    }

    @Test
    @DisplayName("供给缺口 · 非法区间档位 → 400")
    void gapShouldRejectInvalidRange() throws Exception {
        mockMvc.perform(get("/api/admin/operations/gap")
                        .header(AUTH_HEADER, admin())
                        .param("range", "LAST_99Y"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }

    /* ================================================================== */
    /* 2 · 干预效果对比                                                    */
    /* ================================================================== */

    @Test
    @DisplayName("干预效果 · 响应结构与 tickets §六 一致")
    void effectResponseShouldMatchContract() throws Exception {
        mockMvc.perform(get("/api/admin/operations/effect")
                        .header(AUTH_HEADER, admin())
                        .param("metric", "operations.fulfillment_rate")
                        .param("from", "2026-08-01")
                        .param("to", "2026-08-15")
                        .param("compareFrom", "2026-07-17")
                        .param("compareTo", "2026-07-31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.metric").value("operations.fulfillment_rate"))
                .andExpect(jsonPath("$.data.metricName").value("履约率"))
                .andExpect(jsonPath("$.data.current.from").value("2026-08-01"))
                .andExpect(jsonPath("$.data.current.to").value("2026-08-15"))
                .andExpect(jsonPath("$.data.current.value").value(matchesPattern("-?\\d+(\\.\\d+)?%?")))
                .andExpect(jsonPath("$.data.previous.from").value("2026-07-17"))
                .andExpect(jsonPath("$.data.previous.to").value("2026-07-31"))
                .andExpect(jsonPath("$.data.previous.value").value(matchesPattern("-?\\d+(\\.\\d+)?%?")))
                .andExpect(jsonPath("$.data.delta").value(matchesPattern("[+-]\\d+(\\.\\d+)?%?|—")));
    }

    @Test
    @DisplayName("干预效果 · 未注册的指标 → 400（不猜一个算法算出来）")
    void effectShouldRejectUnregisteredMetric() throws Exception {
        mockMvc.perform(get("/api/admin/operations/effect")
                        .header(AUTH_HEADER, admin())
                        .param("metric", "operations.not_registered"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @DisplayName("干预效果 · 区间不合法 → 9002")
    void effectShouldRejectInvalidRange() throws Exception {
        mockMvc.perform(get("/api/admin/operations/effect")
                        .header(AUTH_HEADER, admin())
                        .param("metric", "operations.fulfillment_rate")
                        .param("from", "2026-08-15")
                        .param("to", "2026-08-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(9002));
    }

    /* ================================================================== */
    /* 工具                                                                */
    /* ================================================================== */

    private String admin() {
        return TestTokens.bearer(tokenProvider, tokenStore, ADMIN_ID, "ADMIN", "v36");
    }
}

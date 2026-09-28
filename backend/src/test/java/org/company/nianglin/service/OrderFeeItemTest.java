package org.company.nianglin.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.company.nianglin.common.ResultCode;
import org.company.nianglin.constant.RoleConstants;
import org.company.nianglin.entity.CompanionOrder;
import org.company.nianglin.mapper.CompanionOrderMapper;
import org.company.nianglin.security.JwtTokenProvider;
import org.company.nianglin.security.TokenStore;
import org.company.nianglin.support.TestTokens;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M4 费用明细（ADR-0009）：录入 / 查询 / 权限 / 兜底口径。
 *
 * <p>种子靶子（V2，与 OrderAccessMatrixTest 同源）：1001 PENDING；
 * 1007 ACCEPTED·陪诊员307；1025 COMPLETED·家属125·陪诊员301。
 * 写路径全部包在测试事务里回滚，不污染共享开发库。</p>
 *
 * @author 银龄伴诊团队
 * @since M4（收敛迭代 T2.5）
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("M4 费用明细：代垫/服务费分账（ADR-0009）")
class OrderFeeItemTest {

    private static final String AUTH_HEADER = "Authorization";
    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;

    private static final long ADMIN_ID = 1L;
    /** 1025 的下单家属（V2 种子：1025 → family 125，与 OrderAccessMatrixTest 同源） */
    private static final long FAMILY_OF_1025 = 125L;
    private static final long FAMILY_OTHER = 101L;
    private static final long COMPANION_OF_1025 = 301L;
    private static final long COMPANION_OF_1007 = 307L;
    private static final long PENDING_ORDER = 1001L;
    private static final long ACCEPTED_ORDER = 1007L;
    private static final long COMPLETED_ORDER = 1025L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private TokenStore tokenStore;

    @Autowired
    private CompanionOrderMapper orderMapper;

    private String token(long userId, String role) {
        return TestTokens.bearer(tokenProvider, tokenStore, userId, role, "fee-item");
    }

    private String body(String type, String name, String amount) {
        return "{\"itemType\":\"" + type + "\",\"itemName\":\"" + name
                + "\",\"amount\":\"" + amount + "\",\"occurredAt\":\"2026-09-28 10:30:00\"}";
    }

    @Test
    @Transactional
    @DisplayName("录入 · 本单陪诊员给已完成订单记代垫 → 200，itemTypeLabel=代垫")
    void companionShouldCreateAdvanceItem() throws Exception {
        mockMvc.perform(post("/api/order/{id}/fee-items", COMPLETED_ORDER)
                        .header(AUTH_HEADER, token(COMPANION_OF_1025, RoleConstants.COMPANION))
                        .contentType(JSON).content(body("ADVANCE", "心内科挂号费", "35.50")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.itemType").value("ADVANCE"))
                .andExpect(jsonPath("$.data.itemTypeLabel").value("代垫"))
                .andExpect(jsonPath("$.data.amount").value("35.50"));
    }

    @Test
    @Transactional
    @DisplayName("录入 · 完成态订单录入后 actual_fee 重算为明细求和（35.50）")
    void createShouldRecalcActualFeeOnCompletedOrder() throws Exception {
        mockMvc.perform(post("/api/order/{id}/fee-items", COMPLETED_ORDER)
                        .header(AUTH_HEADER, token(COMPANION_OF_1025, RoleConstants.COMPANION))
                        .contentType(JSON).content(body("ADVANCE", "挂号费", "35.50")))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/order/{id}", COMPLETED_ORDER)
                        .header(AUTH_HEADER, token(FAMILY_OF_1025, RoleConstants.FAMILY)))
                .andExpect(jsonPath("$.data.actualFee").value(35.50));
    }

    @Test
    @DisplayName("录入 · 非本单陪诊员 → 4003")
    void otherCompanionShouldReturn4003() throws Exception {
        mockMvc.perform(post("/api/order/{id}/fee-items", COMPLETED_ORDER)
                        .header(AUTH_HEADER, token(COMPANION_OF_1007, RoleConstants.COMPANION))
                        .contentType(JSON).content(body("ADVANCE", "挂号费", "1.00")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.NOT_ORDER_COMPANION.getCode()));
    }

    @Test
    @Transactional
    @DisplayName("录入 · 已接单未开服务 → 3002（IN_SERVICE / COMPLETED 才能记账）")
    void acceptedOrderShouldReturn3002() throws Exception {
        mockMvc.perform(post("/api/order/{id}/fee-items", ACCEPTED_ORDER)
                        .header(AUTH_HEADER, token(COMPANION_OF_1007, RoleConstants.COMPANION))
                        .contentType(JSON).content(body("ADVANCE", "挂号费", "1.00")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.ORDER_STATUS_ILLEGAL.getCode()));
    }

    @Test
    @Transactional
    @DisplayName("录入 · PENDING 订单 → 3002（状态判断先于身份判断）")
    void pendingOrderShouldReturn3002() throws Exception {
        mockMvc.perform(post("/api/order/{id}/fee-items", PENDING_ORDER)
                        .header(AUTH_HEADER, token(COMPANION_OF_1025, RoleConstants.COMPANION))
                        .contentType(JSON).content(body("ADVANCE", "挂号费", "1.00")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.ORDER_STATUS_ILLEGAL.getCode()));
    }

    @Test
    @DisplayName("录入 · 家属调录入端点 → 403（记账是陪诊员的事）")
    void familyShouldBeRejected403() throws Exception {
        mockMvc.perform(post("/api/order/{id}/fee-items", COMPLETED_ORDER)
                        .header(AUTH_HEADER, token(FAMILY_OF_1025, RoleConstants.FAMILY))
                        .contentType(JSON).content(body("ADVANCE", "挂号费", "1.00")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    @Transactional
    @DisplayName("查询 · 相关方家属看到明细与分组合计")
    void ownerFamilyShouldListItems() throws Exception {
        mockMvc.perform(post("/api/order/{id}/fee-items", COMPLETED_ORDER)
                        .header(AUTH_HEADER, token(COMPANION_OF_1025, RoleConstants.COMPANION))
                        .contentType(JSON).content(body("ADVANCE", "挂号费", "35.50")))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/order/{id}/fee-items", COMPLETED_ORDER)
                        .header(AUTH_HEADER, token(COMPANION_OF_1025, RoleConstants.COMPANION))
                        .contentType(JSON).content(body("SERVICE", "陪诊服务费", "128.00")))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/order/{id}/fee-items", COMPLETED_ORDER)
                        .header(AUTH_HEADER, token(FAMILY_OF_1025, RoleConstants.FAMILY)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(2)))
                .andExpect(jsonPath("$.data.advanceTotal").value("35.50"))
                .andExpect(jsonPath("$.data.serviceTotal").value("128.00"))
                .andExpect(jsonPath("$.data.total").value("163.50"))
                .andExpect(jsonPath("$.data.hasItems").value(true));
    }

    @Test
    @DisplayName("查询 · 无关家属 → 3004")
    void otherFamilyShouldReturn3004() throws Exception {
        mockMvc.perform(get("/api/order/{id}/fee-items", COMPLETED_ORDER)
                        .header(AUTH_HEADER, token(FAMILY_OTHER, RoleConstants.FAMILY)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.ORDER_NO_PERMISSION.getCode()));
    }

    @Test
    @Transactional
    @DisplayName("兜底 · 无明细且已有申报金额 → fallbackNotice 显式提示，不伪造明细")
    void emptyItemsWithDeclaredFeeShouldReturnFallbackNotice() throws Exception {
        // 构造确定前置：1025 无明细 + actual_fee 有值（测试事务内固定，结束回滚）
        assertTrue(orderMapper.update(null, Wrappers.<CompanionOrder>lambdaUpdate()
                .eq(CompanionOrder::getId, COMPLETED_ORDER)
                .set(CompanionOrder::getActualFee, new java.math.BigDecimal("128.00"))) > 0);

        mockMvc.perform(get("/api/order/{id}/fee-items", COMPLETED_ORDER)
                        .header(AUTH_HEADER, token(ADMIN_ID, RoleConstants.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.hasItems").value(false))
                .andExpect(jsonPath("$.data.fallbackNotice").value(containsString("无明细")));
    }

    @Test
    @DisplayName("查询 · 订单不存在 → 3001")
    void missingOrderShouldReturn3001() throws Exception {
        mockMvc.perform(get("/api/order/{id}/fee-items", 999999L)
                        .header(AUTH_HEADER, token(ADMIN_ID, RoleConstants.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.ORDER_NOT_FOUND.getCode()));
    }

    @Test
    @Transactional
    @DisplayName("写入路径 · 完成订单随状态变更生成服务费明细，明细求和 == actual_fee")
    void completeShouldWriteServiceItemAndSum() throws Exception {
        // 1007（ACCEPTED，陪诊员 307）→ start → complete(fee=199.00)
        mockMvc.perform(post("/api/order/{id}/start", ACCEPTED_ORDER)
                        .header(AUTH_HEADER, token(COMPANION_OF_1007, RoleConstants.COMPANION)))
                .andExpect(jsonPath("$.code").value(200));
        mockMvc.perform(post("/api/order/{id}/complete", ACCEPTED_ORDER)
                        .header(AUTH_HEADER, token(COMPANION_OF_1007, RoleConstants.COMPANION))
                        .contentType(JSON)
                        .content("{\"summary\":\"压测完成小结\",\"fee\":\"199.00\"}"))
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(get("/api/order/{id}/fee-items", ACCEPTED_ORDER)
                        .header(AUTH_HEADER, token(COMPANION_OF_1007, RoleConstants.COMPANION)))
                .andExpect(jsonPath("$.data.serviceTotal").value("199.00"))
                .andExpect(jsonPath("$.data.total").value("199.00"))
                .andExpect(jsonPath("$.data.hasItems").value(true));
        // 明细求和 == actual_fee（ADR-0009 后果节要求的单测）
        mockMvc.perform(get("/api/order/{id}", ACCEPTED_ORDER)
                        .header(AUTH_HEADER, token(107L, RoleConstants.FAMILY)))
                .andExpect(jsonPath("$.data.actualFee").value(199.00));
    }
}

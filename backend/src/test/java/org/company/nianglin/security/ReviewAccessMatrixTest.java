package org.company.nianglin.security;

import org.company.nianglin.common.ResultCode;
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

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M7 评价与投诉越权矩阵：4 角色 × 4 类接口（写评价 / 读评价 / 公开口碑 / 投诉）。
 *
 * <h3>M7 的两条不同防线，必须分开测</h3>
 *
 * <ul>
 *   <li><b>评价写接口</b>用 {@code @PreAuthorize("hasRole('FAMILY')")} 收口 ——
 *       陪诊员给自己刷分、老人代评价都会污染评分，所以角色门槛写在注解上；
 *       但注解只说明「你是家属」，说明不了「这单是不是你下的」，
 *       第二道归属校验（{@code order.getFamilyId()}）同样不可省。</li>
 *   <li><b>投诉的三个接口一个 {@code @PreAuthorize} 都没有</b> ——
 *       投诉的可见范围由「订单关系」决定（家属投诉陪诊员 / 陪诊员投诉家属），
 *       注解表达不了「这笔订单是不是你的」。既然 Service 无论如何都要判归属，
 *       再叠一层角色注解只会让「403 还是 3004 先返回」变得难以预测。
 *       老人侧由 {@code ElderReadOnlyInterceptor} 兜底，POST 一律 403。</li>
 * </ul>
 *
 * <h3>依赖的种子数据</h3>
 *
 * <p>订单 <b>1001</b>：家属 101 下单、老人 401、无陪诊员、状态 <b>PENDING</b>。
 * 家属 102 与它无关 —— 用它来区分「你无权评价这一单（3004）」
 * 与「这一单还不能评价（6001）」这两件完全不同的事。</p>
 *
 * <p>投诉 <b>31002</b>：投诉人 120、被投诉人 320 —— 家属 101 既不是投诉人也不是被投诉人，
 * 查详情应得 403（越权）而不是 6004（不存在）。这两个码在实现里被刻意分开，
 * 本类把它们锁死。</p>
 *
 * <p>本类只发「必然被拒」的写请求与只读请求，不改动任何数据，可随时重跑。</p>
 *
 * @author 银龄伴诊团队
 * @since M7
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("M7 评价与投诉越权矩阵：4 角色 × 4 类接口")
class ReviewAccessMatrixTest {

    private static final String AUTH_HEADER = "Authorization";
    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;

    /** 靶子订单：家属 101、老人 401、无陪诊员、PENDING */
    private static final long ORDER_OF_101 = 1001L;
    private static final long FAMILY_OWNER = 101L;
    private static final long FAMILY_OTHER = 102L;
    private static final long ELDER_OF_101 = 201L;
    private static final long COMPANION_301 = 301L;
    private static final long ADMIN_ID = 1L;

    /** 口碑数据靶子：陪诊员 301 有历史评价 */
    private static final long COMPANION_WITH_REVIEWS = 301L;
    /** 与 101 / 201 / 301 都无关的他人投诉 */
    private static final long OTHERS_COMPLAINT = 31002L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private TokenStore tokenStore;

    /* ================================================================== */
    /* 1 · 提交评价（写）：只有下单家属，且订单须已完成                        */
    /* ================================================================== */

    @ParameterizedTest(name = "提交评价 · {0} → 403")
    @ValueSource(strings = {RoleConstants.ELDER, RoleConstants.COMPANION, RoleConstants.ADMIN})
    @DisplayName("提交评价 · 老人 / 陪诊员 / 管理员提交评价 → 403（防刷分，评价只由下单家属发出）")
    void nonFamilyShouldNotCreateReview(String role) throws Exception {
        mockMvc.perform(post("/api/review")
                        .header(AUTH_HEADER, bearer(userIdOf(role), role))
                        .contentType(JSON)
                        .content(reviewBody()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    @DisplayName("提交评价 · 老人提交被「只读模式」拦下 → 403 且提示解释原因")
    void elderCreateReviewShouldBeBlockedByReadOnlyRule() throws Exception {
        mockMvc.perform(post("/api/review")
                        .header(AUTH_HEADER, elder(ELDER_OF_101))
                        .contentType(JSON)
                        .content(reviewBody()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.message").value(containsString("只读")));
    }

    @Test
    @DisplayName("提交评价 · 未登录 → 401")
    void anonymousShouldGet401OnCreateReview() throws Exception {
        mockMvc.perform(post("/api/review").contentType(JSON).content(reviewBody()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    @DisplayName("提交评价 · 家属评价别人下的单 → 3004（角色对，但并不涉及这一单）")
    void otherFamilyShouldNotCreateReview() throws Exception {
        // 关键：不能因为「有 @PreAuthorize 就够了」而漏掉 Service 层的
        // order.getFamilyId() 比对 —— 注解挡不住家属 A 评价家属 B 的单
        mockMvc.perform(post("/api/review")
                        .header(AUTH_HEADER, family(FAMILY_OTHER))
                        .contentType(JSON)
                        .content(reviewBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.ORDER_NO_PERMISSION.getCode()));
    }

    @Test
    @DisplayName("提交评价 · 下单家属评价一笔未完成的单 → 6001（与 3004 是两回事）")
    void ownerFamilyShouldNotReviewPendingOrder() throws Exception {
        // 订单 1001 是 PENDING。归属通过了，但状态闸门要说「不能评价」
        mockMvc.perform(post("/api/review")
                        .header(AUTH_HEADER, family(FAMILY_OWNER))
                        .contentType(JSON)
                        .content(reviewBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.ORDER_NOT_COMPLETED.getCode()));
    }

    @Test
    @DisplayName("提交评价 · 缺少必填项（评分）→ 400，且参数校验先于鉴权之外的一切业务判断")
    void invalidReviewBodyShouldReturn400() throws Exception {
        mockMvc.perform(post("/api/review")
                        .header(AUTH_HEADER, family(FAMILY_OWNER))
                        .contentType(JSON)
                        .content("{\"orderId\":1001}"))
                .andExpect(jsonPath("$.code").value(ResultCode.PARAM_ERROR.getCode()));
    }

    /* ================================================================== */
    /* 2 · 查询订单评价（读）：须为订单相关方，否则 3004                       */
    /* ================================================================== */

    @Test
    @DisplayName("读评价 · 下单家属读自己订单的评价 → 200")
    void ownerFamilyShouldReadReview() throws Exception {
        mockMvc.perform(get("/api/review/order/{orderId}", ORDER_OF_101)
                        .header(AUTH_HEADER, family(FAMILY_OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    @DisplayName("读评价 · 就诊老人本人读自己订单的评价 → 200")
    void elderShouldReadOwnOrderReview() throws Exception {
        mockMvc.perform(get("/api/review/order/{orderId}", ORDER_OF_101)
                        .header(AUTH_HEADER, elder(ELDER_OF_101)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    @DisplayName("读评价 · 无关家属读别人的订单评价 → 3004（读接口没有角色注解）")
    void otherFamilyShouldNotReadReview() throws Exception {
        mockMvc.perform(get("/api/review/order/{orderId}", ORDER_OF_101)
                        .header(AUTH_HEADER, family(FAMILY_OTHER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.ORDER_NO_PERMISSION.getCode()));
    }

    @Test
    @DisplayName("读评价 · 订单不存在 → 3001（与「无权」和「还没评价」三码分开）")
    void missingOrderShouldReturn3001() throws Exception {
        mockMvc.perform(get("/api/review/order/{orderId}", 999999L)
                        .header(AUTH_HEADER, family(FAMILY_OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.ORDER_NOT_FOUND.getCode()));
    }

    @Test
    @DisplayName("读评价 · 未登录 → 401")
    void anonymousShouldGet401OnReadReview() throws Exception {
        mockMvc.perform(get("/api/review/order/{orderId}", ORDER_OF_101))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    /* ================================================================== */
    /* 3 · 陪诊员口碑（读）：公开数据，四个角色都应放行                        */
    /* ================================================================== */

    @ParameterizedTest(name = "口碑评分 · {0} → 200")
    @ValueSource(strings = {RoleConstants.ELDER, RoleConstants.FAMILY, RoleConstants.COMPANION, RoleConstants.ADMIN})
    @DisplayName("口碑评分 · 四个角色都能看（家属选人要看，天然公开）")
    void scoreShouldAllowEveryRole(String role) throws Exception {
        mockMvc.perform(get("/api/review/companion/{id}/score", COMPANION_WITH_REVIEWS)
                        .header(AUTH_HEADER, bearer(userIdOf(role), role)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @ParameterizedTest(name = "口碑列表 · {0} → 200")
    @ValueSource(strings = {RoleConstants.ELDER, RoleConstants.FAMILY, RoleConstants.COMPANION, RoleConstants.ADMIN})
    @DisplayName("口碑列表 · 四个角色都能看（仅剔除了被管理员判无效的评价）")
    void companionReviewPageShouldAllowEveryRole(String role) throws Exception {
        mockMvc.perform(get("/api/review/companion/{id}", COMPANION_WITH_REVIEWS)
                        .header(AUTH_HEADER, bearer(userIdOf(role), role)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    @DisplayName("口碑 · 未登录看陪诊员评分 → 401")
    void anonymousShouldGet401OnScore() throws Exception {
        mockMvc.perform(get("/api/review/companion/{id}/score", COMPANION_WITH_REVIEWS))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    /* ================================================================== */
    /* 4 · 投诉：无角色注解，可见范围完全由订单关系决定                        */
    /* ================================================================== */

    @Test
    @DisplayName("提交投诉 · 老人提交投诉 → 403 只读（投诉必须由家属或陪诊员发起）")
    void elderShouldNotCreateComplaint() throws Exception {
        mockMvc.perform(post("/api/complaint")
                        .header(AUTH_HEADER, elder(ELDER_OF_101))
                        .contentType(JSON)
                        .content(complaintBody()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.message").value(containsString("只读")));
    }

    @Test
    @DisplayName("提交投诉 · 未登录 → 401")
    void anonymousShouldGet401OnCreateComplaint() throws Exception {
        mockMvc.perform(post("/api/complaint").contentType(JSON).content(complaintBody()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    @DisplayName("提交投诉 · 家属投诉与自己无关的订单 → 3004（投诉人/被投诉人由订单推导，不能伪造）")
    void otherFamilyShouldNotCreateComplaint() throws Exception {
        mockMvc.perform(post("/api/complaint")
                        .header(AUTH_HEADER, family(FAMILY_OTHER))
                        .contentType(JSON)
                        .content(complaintBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.ORDER_NO_PERMISSION.getCode()));
    }

    @Test
    @DisplayName("提交投诉 · 陪诊员投诉与自己无关的订单 → 3004（陪诊员也不能凭空投诉）")
    void companionShouldNotCreateComplaintOnUnrelatedOrder() throws Exception {
        mockMvc.perform(post("/api/complaint")
                        .header(AUTH_HEADER, companion(COMPANION_301))
                        .contentType(JSON)
                        .content(complaintBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.ORDER_NO_PERMISSION.getCode()));
    }

    @Test
    @DisplayName("我的投诉列表 · 家属读自己的投诉列表 → 200（可见范围不超出本人相关投诉）")
    void familyShouldReadOwnComplaintList() throws Exception {
        mockMvc.perform(get("/api/complaint").header(AUTH_HEADER, family(FAMILY_OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    @DisplayName("投诉详情 · 无关家属查别人的投诉 → 403（越权，而不是 6004 不存在）")
    void unrelatedFamilyShouldGet403OnComplaintDetail() throws Exception {
        // 把「不给我看」和「确实没了」合成同一个码，排查时无从下手；
        // 这里锁死「越权 = 403」这条契约
        mockMvc.perform(get("/api/complaint/{id}", OTHERS_COMPLAINT)
                        .header(AUTH_HEADER, family(FAMILY_OWNER)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    @DisplayName("投诉详情 · 管理员可查任意投诉 → 200（纠纷处理需要全量可见）")
    void adminShouldReadAnyComplaintDetail() throws Exception {
        mockMvc.perform(get("/api/complaint/{id}", OTHERS_COMPLAINT)
                        .header(AUTH_HEADER, admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    @DisplayName("投诉详情 · 记录不存在 → 6004")
    void missingComplaintShouldReturn6004() throws Exception {
        mockMvc.perform(get("/api/complaint/{id}", 999999L)
                        .header(AUTH_HEADER, admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.COMPLAINT_NOT_FOUND.getCode()));
    }

    @Test
    @DisplayName("投诉详情 · 未登录 → 401")
    void anonymousShouldGet401OnComplaintDetail() throws Exception {
        mockMvc.perform(get("/api/complaint/{id}", OTHERS_COMPLAINT))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    /* ================================================================== */

    /** 一份格式合法的评价请求体（越权用例中它永远到不了写库那一步） */
    private static String reviewBody() {
        return "{\"orderId\":1001,\"score\":5}";
    }

    /** 一份格式合法的投诉请求体（内容长度满足 10–1000 字符） */
    private static String complaintBody() {
        return "{\"orderId\":1001,\"type\":\"LATE\","
                + "\"content\":\"陪诊员比约定时间晚了 40 分钟到达，导致老人错过取号。\"}";
    }

    private long userIdOf(String role) {
        return switch (role) {
            case RoleConstants.ELDER -> ELDER_OF_101;
            case RoleConstants.FAMILY -> FAMILY_OWNER;
            case RoleConstants.COMPANION -> COMPANION_301;
            default -> ADMIN_ID;
        };
    }

    private String family(long userId) {
        return bearer(userId, RoleConstants.FAMILY);
    }

    private String elder(long userId) {
        return bearer(userId, RoleConstants.ELDER);
    }

    private String companion(long userId) {
        return bearer(userId, RoleConstants.COMPANION);
    }

    private String admin() {
        return bearer(ADMIN_ID, RoleConstants.ADMIN);
    }

    private String bearer(long userId, String role) {
        return TestTokens.bearer(tokenProvider, tokenStore, userId, role, "m7");
    }
}

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M6 用药管理越权矩阵：4 角色 × 3 类接口（药品字典 / 用药计划 / 服药任务）。
 *
 * <h3>为什么 M6 比 M4 更需要这张矩阵</h3>
 *
 * <p>用药计划里存的是「谁在吃哪种药、一天几次、什么时候吃」——
 * 这比订单信息（只暴露「去过医院」）敏感一个量级，它直接就是病情线索。
 * 而 M6 的控制器把三类接口的门槛分成了三档：</p>
 *
 * <table border="1">
 *   <caption>接口与门槛</caption>
 *   <tr><th>接口</th><th>门槛</th><th>防线落点</th></tr>
 *   <tr><td>药品字典（{@code /dict}）</td><td>仅「已登录」</td>
 *       <td>公开通用资料，无归属概念</td></tr>
 *   <tr><td>计划写（POST/PUT/DELETE）</td><td>{@code hasRole('FAMILY')}</td>
 *       <td>注解 + Service 归属，两层都要有</td></tr>
 *   <tr><td>计划读 / 日历 / 今日待服</td><td>仅「已登录」</td>
 *       <td><b>只有 Service 归属校验</b> —— 第 3 组专盯这里</td></tr>
 * </table>
 *
 * <h3>依赖的种子数据</h3>
 *
 * <p>老人档案 <b>401（张德海，用户 201）</b> 由家属 <b>101</b> 绑定
 * （{@code family_elder_relation#501}），计划 10001 即由 101 创建。
 * 家属 <b>102</b> 绑的是档案 402 —— 他拿着一张完全合法的家属令牌，
 * 角色正确、账号真实，唯一能挡住他读 401 用药计划的只有归属校验。</p>
 *
 * <p>服药任务 20002（老人 401、状态 PENDING）用于验证写路径上的归属：
 * 家属 102 去确认 401 的药，必须先被归属拦下。</p>
 *
 * <p>本类只发「必然被拒」的写请求与只读请求，不改动任何数据，可随时重跑。
 * 唯一一处写接口的「正面用例」（家属读自己老人的计划）也是只读请求。</p>
 *
 * @author 银龄伴诊团队
 * @since M6
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("M6 用药管理越权矩阵：4 角色 × 3 类接口")
class MedicationAccessMatrixTest {

    private static final String AUTH_HEADER = "Authorization";
    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;

    /** 靶子老人档案：家属 101 绑定、用户 201 */
    private static final long ELDER_PROFILE_OF_101 = 401L;
    private static final long ELDER_USER_OF_101 = 201L;
    /** 另一个家属：绑的是档案 402，与 401 无关 */
    private static final long FAMILY_OWNER = 101L;
    private static final long FAMILY_OTHER = 102L;
    private static final long COMPANION_301 = 301L;
    private static final long ADMIN_ID = 1L;

    /** 老人 401 的「待服」任务（早于今天，但状态仍是 PENDING） */
    private static final long PENDING_TASK_OF_401 = 20002L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private TokenStore tokenStore;

    /* ================================================================== */
    /* 1 · 药品字典（读）：登录即可，无归属概念                              */
    /* ================================================================== */

    @ParameterizedTest(name = "药品字典 · {0} → 200")
    @ValueSource(strings = {RoleConstants.ELDER, RoleConstants.FAMILY, RoleConstants.COMPANION, RoleConstants.ADMIN})
    @DisplayName("药品字典 · 四个角色都能查（公共通用资料，不含任何用药建议）")
    void dictShouldAllowEveryRole(String role) throws Exception {
        mockMvc.perform(get("/api/medication/dict").param("pageSize", "5")
                        .header(AUTH_HEADER, bearer(userIdOf(role), role)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    @DisplayName("药品字典 · 未登录 → 401（字典不是匿名接口）")
    void anonymousShouldGet401OnDict() throws Exception {
        mockMvc.perform(get("/api/medication/dict"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    @DisplayName("药品详情 · 药品不存在 → 5001")
    void missingMedicineShouldReturn5001() throws Exception {
        mockMvc.perform(get("/api/medication/dict/{id}", 999999L)
                        .header(AUTH_HEADER, family(FAMILY_OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.MEDICINE_NOT_FOUND.getCode()));
    }

    /* ================================================================== */
    /* 2 · 用药计划写（写）：只有家属能录，老人账号必须代录                    */
    /* ================================================================== */

    @ParameterizedTest(name = "新增计划 · {0} → 403")
    @ValueSource(strings = {RoleConstants.ELDER, RoleConstants.COMPANION, RoleConstants.ADMIN})
    @DisplayName("新增计划 · 老人 / 陪诊员 / 管理员新增用药计划 → 403（只有家属能代录）")
    void nonFamilyShouldNotCreatePlan(String role) throws Exception {
        mockMvc.perform(post("/api/medication/plan")
                        .header(AUTH_HEADER, bearer(userIdOf(role), role))
                        .contentType(JSON)
                        .content(createPlanBody()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    @DisplayName("新增计划 · 老人被「只读模式」拦下 → 403 且提示解释原因")
    void elderCreatePlanShouldBeBlockedByReadOnlyRule() throws Exception {
        mockMvc.perform(post("/api/medication/plan")
                        .header(AUTH_HEADER, elder(ELDER_USER_OF_101))
                        .contentType(JSON)
                        .content(createPlanBody()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.message").value(containsString("只读")));
    }

    @Test
    @DisplayName("新增计划 · 未登录 → 401")
    void anonymousShouldGet401OnCreatePlan() throws Exception {
        mockMvc.perform(post("/api/medication/plan").contentType(JSON).content(createPlanBody()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    @DisplayName("新增计划 · 删计划同样受限：ADMIN 停用用药计划 → 403")
    void adminShouldNotDisablePlan() throws Exception {
        // 管理员能审核资质、能改订单终态，但「写业务数据」的路必须自己走一遍，
        // 否则后台就成了绕过归属校验的后门
        mockMvc.perform(delete("/api/medication/plan/{id}", 10001L)
                        .header(AUTH_HEADER, admin()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    /* ================================================================== */
    /* 3 · 计划读 / 日历 / 今日待服（读）：防线全在 Service 归属校验上          */
    /* ================================================================== */

    @Test
    @DisplayName("归属 · 绑定家属读自己老人的计划列表 → 200")
    void ownerFamilyShouldReadPlanPage() throws Exception {
        mockMvc.perform(get("/api/medication/plan")
                        .param("elderId", String.valueOf(ELDER_PROFILE_OF_101))
                        .header(AUTH_HEADER, family(FAMILY_OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    @DisplayName("归属 · 另一个家属读别人家老人的计划列表 → 2006（读接口唯一的防线）")
    void otherFamilyShouldNotReadPlanPage() throws Exception {
        mockMvc.perform(get("/api/medication/plan")
                        .param("elderId", String.valueOf(ELDER_PROFILE_OF_101))
                        .header(AUTH_HEADER, family(FAMILY_OTHER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.NO_PERMISSION_FOR_ELDER.getCode()));
    }

    @Test
    @DisplayName("归属 · 另一个家属读服药日历 → 2006")
    void otherFamilyShouldNotReadCalendar() throws Exception {
        mockMvc.perform(get("/api/medication/task/calendar")
                        .param("elderId", String.valueOf(ELDER_PROFILE_OF_101))
                        .param("startDate", "2026-09-09")
                        .param("endDate", "2026-09-15")
                        .header(AUTH_HEADER, family(FAMILY_OTHER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.NO_PERMISSION_FOR_ELDER.getCode()));
    }

    @Test
    @DisplayName("归属 · 另一个家属读今日待服 → 2006")
    void otherFamilyShouldNotReadTodayTasks() throws Exception {
        mockMvc.perform(get("/api/medication/task/today")
                        .param("elderId", String.valueOf(ELDER_PROFILE_OF_101))
                        .header(AUTH_HEADER, family(FAMILY_OTHER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.NO_PERMISSION_FOR_ELDER.getCode()));
    }

    @Test
    @DisplayName("归属 · 陪诊员读「没有在途订单」的老人用药计划 → 2006")
    void companionShouldNotReadUnrelatedElder() throws Exception {
        // 301 的在途单子不涉及老人 401；陪诊员只在 PENDING/ACCEPTED/IN_SERVICE 期间
        // 才与老人存在「陪诊关系」，单子结了就不该还能读人家的用药计划
        mockMvc.perform(get("/api/medication/plan")
                        .param("elderId", String.valueOf(ELDER_PROFILE_OF_101))
                        .header(AUTH_HEADER, companion(COMPANION_301)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.NO_PERMISSION_FOR_ELDER.getCode()));
    }

    @Test
    @DisplayName("归属 · 就诊老人本人读自己的计划列表 → 200（老人只读，但读放行）")
    void elderShouldReadOwnPlanPage() throws Exception {
        mockMvc.perform(get("/api/medication/plan")
                        .param("elderId", String.valueOf(ELDER_PROFILE_OF_101))
                        .header(AUTH_HEADER, elder(ELDER_USER_OF_101)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    @DisplayName("读接口 · 未登录读计划列表 → 401")
    void anonymousShouldGet401OnPlanPage() throws Exception {
        mockMvc.perform(get("/api/medication/plan")
                        .param("elderId", String.valueOf(ELDER_PROFILE_OF_101)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    @DisplayName("读接口 · 不传 elderId → 400（「查谁的药」不能省略）")
    void missingElderIdShouldReturn400() throws Exception {
        mockMvc.perform(get("/api/medication/plan").header(AUTH_HEADER, family(FAMILY_OWNER)))
                .andExpect(jsonPath("$.code").value(ResultCode.PARAM_ERROR.getCode()));
    }

    /* ================================================================== */
    /* 4 · 确认服药（写）：家属与陪诊员可代确认，老人本人不行                  */
    /* ================================================================== */

    @Test
    @DisplayName("确认服药 · 老人确认自己的服药任务 → 403 只读（必须由家属代做）")
    void elderShouldNotConfirm() throws Exception {
        mockMvc.perform(post("/api/medication/task/{id}/confirm", PENDING_TASK_OF_401)
                        .header(AUTH_HEADER, elder(ELDER_USER_OF_101))
                        .contentType(JSON)
                        .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.message").value(containsString("只读")));
    }

    @Test
    @DisplayName("确认服药 · 管理员替家属点「已服用」→ 403（不做业务数据的代操作）")
    void adminShouldNotConfirm() throws Exception {
        mockMvc.perform(post("/api/medication/task/{id}/confirm", PENDING_TASK_OF_401)
                        .header(AUTH_HEADER, admin())
                        .contentType(JSON)
                        .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    @DisplayName("确认服药 · 另一个家属确认别人家老人的药 → 2006（写路径同样要过归属）")
    void otherFamilyShouldNotConfirm() throws Exception {
        mockMvc.perform(post("/api/medication/task/{id}/confirm", PENDING_TASK_OF_401)
                        .header(AUTH_HEADER, family(FAMILY_OTHER))
                        .contentType(JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.NO_PERMISSION_FOR_ELDER.getCode()));
    }

    @Test
    @DisplayName("确认服药 · 未登录 → 401")
    void anonymousShouldGet401OnConfirm() throws Exception {
        mockMvc.perform(post("/api/medication/task/{id}/confirm", PENDING_TASK_OF_401)
                        .contentType(JSON).content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    @DisplayName("确认服药 · 任务不存在 → 404（与越权码分开，便于排查）")
    void missingTaskShouldReturn404() throws Exception {
        mockMvc.perform(post("/api/medication/task/{id}/confirm", 999999L)
                        .header(AUTH_HEADER, family(FAMILY_OWNER))
                        .contentType(JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.NOT_FOUND.getCode()));
    }

    /* ================================================================== */

    /** 一份格式合法的用药计划请求体（越权用例中它永远到不了业务层） */
    private static String createPlanBody() {
        return "{\"elderId\":401,\"medicineId\":801,\"dosage\":\"1 片\",\"frequency\":1,"
                + "\"timePoints\":[\"08:00\"],\"startDate\":\"2026-09-17\",\"mealRelation\":\"AFTER_MEAL\"}";
    }

    private long userIdOf(String role) {
        return switch (role) {
            case RoleConstants.ELDER -> ELDER_USER_OF_101;
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
        return TestTokens.bearer(tokenProvider, tokenStore, userId, role, "m6");
    }
}

package org.company.nianglin.security;

import org.company.nianglin.common.ResultCode;
import org.company.nianglin.constant.RedisKeyConstants;
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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 用户模块越权矩阵：4 角色 × 3 类（未登录 / 越权读他人 / 越权写）= 12 条。
 *
 * <p><b>为什么存在</b>：M2 的 12 条验收用例原先挂在专门的权限探针接口上；
 * 探针上；探针的使命在各业务模块落地后结束（收敛迭代 T2.3）。删除探针的
 * <b>前置条件</b>是同等强度的回归网已织进真实业务接口 —— 即本类与
 * {@code OrderAccessMatrixTest} 等 7 个模块矩阵。</p>
 *
 * <h3>12 条用例矩阵（与类内测试一一对应）</h3>
 * <ol>
 *   <li>① 未登录 × 4 端点形态 → 401（用例 1-4）</li>
 *   <li>② 越权写：ELDER 被「只读规则」拦 2 条（用例 5-6）；FAMILY/COMPANION/ADMIN 放行对照 3 条（用例 7-9）</li>
 *   <li>③ 越权读：公开资料边界 3 条 —— 不存在 2007 / 未过审 2007 / 从未申请 data 缺省（用例 10-12）</li>
 * </ol>
 *
 * <p>探针的「写接口四角色全被拒」语义由 Order 等模块矩阵的「老人只读」段继承，
 * 这里用真实业务写接口（{@code PUT /profile}）表达同一规则。</p>
 *
 * @author 银龄伴诊团队
 * @since 收敛迭代 T2.3
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("M2/M3 用户模块越权矩阵：4 角色 × 3 类 = 12 条（探针退场的回归网）")
class UserAccessMatrixTest {

    private static final String AUTH_HEADER = "Authorization";
    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;

    private static final long ADMIN_ID = 1L;
    private static final long FAMILY_ID = 101L;
    private static final long ELDER_ID = 201L;
    private static final long COMPANION_APPROVED = 301L;
    /** 种子里 audit_status = PENDING 的陪诊员（V2 声明，与 OrderAccessMatrixTest 同源） */
    private static final long COMPANION_PENDING = 325L;
    private static final long NOT_EXIST_COMPANION = 999999L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    /** 密码版本必须读实时值 —— 登出会 bump 版本，写死 0 会被历史遗留状态击穿 */
    @Autowired
    private TokenStore tokenStore;

    /** 注销用例抬升 pwd:version 后要在 finally 里清键 —— Redis 不随 @Transactional 回滚 */
    @Autowired
    private StringRedisTemplate redisTemplate;

    /* ============ ① 未登录（4 条）：所有端点形态都对匿名关闭 ============ */

    @ParameterizedTest(name = "未登录 · {0} → 401")
    @CsvSource({
            "GET,/api/user/profile",
            "PUT,/api/user/profile",
            "POST,/api/user/companion/apply",
            "GET,/api/user/companion/301"})
    @DisplayName("未登录 · 用户模块 4 类端点 → 401")
    void anonymousShouldGet401OnEveryEndpointShape(String method, String path) throws Exception {
        var request = switch (method) {
            case "GET" -> get(path);
            case "PUT" -> put(path).contentType(JSON).content("{}");
            case "POST" -> post(path).contentType(JSON).content("{}");
            default -> throw new IllegalArgumentException(method);
        };
        mockMvc.perform(request)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    /* ============ ② 越权写（5 条）：ELDER 只读 + 三角色对照 ============ */

    @Test
    @DisplayName("越权写 · 老人改资料 → 403，且提示语解释「只读」")
    void elderShouldBeBlockedOnUpdateProfile() throws Exception {
        mockMvc.perform(put("/api/user/profile").header(AUTH_HEADER, elder())
                        .contentType(JSON).content("{\"nickname\":\"老人改名\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.message").value(containsString("只读")));
    }

    @Test
    @DisplayName("越权写 · 老人提交陪诊员申请 → 403（只读规则只有一条，没有例外清单）")
    void elderShouldBeBlockedOnCompanionApply() throws Exception {
        mockMvc.perform(post("/api/user/companion/apply").header(AUTH_HEADER, elder())
                        .contentType(JSON).content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.message").value(containsString("只读")));
    }

    @ParameterizedTest(name = "越权写对照 · {0} 改自己资料 → 200")
    @ValueSource(strings = {RoleConstants.FAMILY, RoleConstants.COMPANION, RoleConstants.ADMIN})
    @Transactional
    @DisplayName("越权写对照 · 三角色改自己昵称 → 200（测试事务回滚，不留痕）")
    void nonElderShouldUpdateOwnProfile(String role) throws Exception {
        long userId = switch (role) {
            case RoleConstants.FAMILY -> FAMILY_ID;
            case RoleConstants.COMPANION -> COMPANION_APPROVED;
            default -> ADMIN_ID;
        };
        mockMvc.perform(put("/api/user/profile").header(AUTH_HEADER, token(userId, role))
                        .contentType(JSON).content("{\"nickname\":\"矩阵测试昵称\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    /* ============ ③ 越权读 / 公开资料边界（3 条） ============ */

    @Test
    @DisplayName("越权读 · 陪诊员公开资料不存在 → 2007")
    void missingCompanionShouldReturn2007() throws Exception {
        mockMvc.perform(get("/api/user/companion/{id}", NOT_EXIST_COMPANION)
                        .header(AUTH_HEADER, token(FAMILY_ID, RoleConstants.FAMILY)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.COMPANION_NOT_FOUND.getCode()));
    }

    @Test
    @DisplayName("越权读 · 未过审的陪诊员对外不算陪诊员 → 2007（不泄露审核中的人）")
    void pendingCompanionShouldNotBePublic() throws Exception {
        mockMvc.perform(get("/api/user/companion/{id}", COMPANION_PENDING)
                        .header(AUTH_HEADER, token(FAMILY_ID, RoleConstants.FAMILY)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.COMPANION_NOT_FOUND.getCode()));
    }

    @Test
    @DisplayName("越权读 · 从未申请过资质 → data 缺省（non_null），不是报错")
    void neverAppliedShouldReturnNullData() throws Exception {
        // fam001 在种子里没有 companion_audit_record
        mockMvc.perform(get("/api/user/companion/application")
                        .header(AUTH_HEADER, token(FAMILY_ID, RoleConstants.FAMILY)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    /* ============ ④ 接单状态自助控制（V3.1 · T0） ============ */

    /**
     * spec §7.3 要求：切换接单状态对 FAMILY / ELDER / ADMIN 一律 403。
     *
     * <p>端点上挂的是 {@code hasRole('COMPANION')}，靠注解就能拦 ——
     * 但注解一旦被人「顺手」改成 {@code isAuthenticated()}，这里必须立刻变红，
     * 因为那会让家属也能把陪诊员切成休息中。</p>
     */
    @ParameterizedTest(name = "非陪诊员切换接单状态 · {0} → 403")
    @CsvSource({"FAMILY,101", "ELDER,201", "ADMIN,1"})
    @DisplayName("切换接单状态 · 非陪诊员角色一律 403（含老人的只读规则叠加）")
    void nonCompanionShouldNotSwitchWorkStatus(String role, long userId) throws Exception {
        mockMvc.perform(put("/api/user/companion/work-status")
                        .contentType(JSON)
                        .content("{\"workStatus\":\"REST\"}")
                        .header(AUTH_HEADER, token(userId, role)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    @DisplayName("切换接单状态 · 未登录 → 401")
    void anonymousShouldNotSwitchWorkStatus() throws Exception {
        mockMvc.perform(put("/api/user/companion/work-status")
                        .contentType(JSON)
                        .content("{\"workStatus\":\"REST\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    @DisplayName("切换接单状态 · 资质未过审的陪诊员 → 2003（角色对但没资格）")
    void unauditedCompanionShouldBeRejected() throws Exception {
        // 角色是 COMPANION 但 audit_status = PENDING —— 注解放行、业务层必须拦下
        mockMvc.perform(put("/api/user/companion/work-status")
                        .contentType(JSON)
                        .content("{\"workStatus\":\"REST\"}")
                        .header(AUTH_HEADER, token(COMPANION_PENDING, RoleConstants.COMPANION)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.COMPANION_NOT_AUDITED.getCode()));
    }

    /* ============ ⑤ 注销账号与数据导出（V4 · P0-1） ============ */

    /**
     * 注销端点挂的是 {@code hasAnyRole('FAMILY','COMPANION')}：
     * ELDER 先到只读拦截器（非 GET 一律 403），ADMIN 被注解拦下（走后台封禁另一条路径）。
     * 两条路径殊途同归都是 403，但<b>不能合并成一个断言糊弄过去</b> —— 角色串进 CsvSource
     * 逐角色跑，谁在哪个环节被拦都有独立用例名可查。
     */
    @ParameterizedTest(name = "注销 · {0} → 403")
    @CsvSource({"ELDER,201", "ADMIN,1"})
    @DisplayName("注销 · 老人（只读）与管理员（无自助注销入口）→ 403")
    void elderOrAdminShouldNotDeactivate(String role, long userId) throws Exception {
        mockMvc.perform(post("/api/user/deactivate")
                        .contentType(JSON)
                        .content("{\"password\":\"Nl@123456\",\"confirmText\":\"我已知晓注销后果\"}")
                        .header(AUTH_HEADER, token(userId, role)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    @DisplayName("注销 · 未登录 → 401")
    void anonymousShouldNotDeactivate() throws Exception {
        mockMvc.perform(post("/api/user/deactivate")
                        .contentType(JSON)
                        .content("{\"password\":\"Nl@123456\",\"confirmText\":\"我已知晓注销后果\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    @DisplayName("注销 · 确认文本不符 → 400，提示语给出要原样输入的固定串")
    @Transactional
    void deactivateShouldRejectWrongConfirmText() throws Exception {
        mockMvc.perform(post("/api/user/deactivate")
                        .contentType(JSON)
                        .content("{\"password\":\"Nl@123456\",\"confirmText\":\"我同意\"}")
                        .header(AUTH_HEADER, token(FAMILY_ID, RoleConstants.FAMILY)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.PARAM_ERROR.getCode()))
                .andExpect(jsonPath("$.message").value(containsString("我已知晓注销后果")));
    }

    @Test
    @DisplayName("注销 · 密码错误 → 1006（与修改密码同一口径）")
    @Transactional
    void deactivateShouldRejectWrongPassword() throws Exception {
        mockMvc.perform(post("/api/user/deactivate")
                        .contentType(JSON)
                        .content("{\"password\":\"WrongPass123\",\"confirmText\":\"我已知晓注销后果\"}")
                        .header(AUTH_HEADER, token(FAMILY_ID, RoleConstants.FAMILY)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.OLD_PASSWORD_ERROR.getCode()));
    }

    /**
     * GET 请求不走老人只读拦截器（那道闸只拦写），所以 {@code data-export} 对 ELDER 的 403
     * 只能来自 {@code @PreAuthorize("hasAnyRole('FAMILY','COMPANION')")} —— 这条用例锁的就是
     * 「GET 端点不能只靠只读拦截器兜底，注解必须自己显式排除 ELDER」。
     */
    @ParameterizedTest(name = "数据导出 · {0} → 403")
    @CsvSource({"ELDER,201", "ADMIN,1"})
    @DisplayName("数据导出 · 老人与管理员 → 403（只能导出本人业务数据）")
    void elderOrAdminShouldNotExport(String role, long userId) throws Exception {
        mockMvc.perform(get("/api/user/data-export")
                        .header(AUTH_HEADER, token(userId, role)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    @DisplayName("数据导出 · 未登录 → 401")
    void anonymousShouldNotExport() throws Exception {
        mockMvc.perform(get("/api/user/data-export"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    /**
     * 端到端走完「注册 → 注销 → 旧令牌失效」全链路：
     * 临时账号（无订单）注销成功后，它注销前签发的 JWT 必须拿到 401 而不是 200 ——
     * 这是「密码版本 +1 踢全端」语义在注销场景的直接证据。
     *
     * <p>⚠️ 两个清理面：DB 写靠 {@code @Transactional} 回滚；
     * Redis 的 {@code pwd:version:{userId}} 不随事务回滚，必须在 finally 里删掉，
     * 否则这个一次性 userId 的脏键会永久留在共享 Redis 里（参见 TestTokens 类注释）。</p>
     */
    @Test
    @DisplayName("注销 · 成功后旧令牌 401（密码版本 +1 踢全端）")
    @Transactional
    void oldTokenShouldDieAfterDeactivate() throws Exception {
        // ① 取真实验证码（与 e2e fresh_captcha 同手法：key 由接口签发，码从 Redis 读，
        //    validate 的 getAndDelete 在注册提交时才消耗，先读出来不影响校验）
        MvcResult captchaResult = mockMvc.perform(get("/api/auth/captcha"))
                .andExpect(status().isOk())
                .andReturn();
        String captchaKey = com.jayway.jsonpath.JsonPath.read(
                captchaResult.getResponse().getContentAsString(), "$.data.captchaKey");
        String captchaCode = redisTemplate.opsForValue().get(RedisKeyConstants.captcha(captchaKey));

        // ② 注册一个全新家属（无订单，必过 11001 闸门）
        MvcResult registered = mockMvc.perform(post("/api/auth/register")
                        .contentType(JSON)
                        .content("{\"username\":\"mxdeact01\",\"phone\":\"13977776666\","
                                + "\"password\":\"Nl@123456\",\"nickname\":\"矩阵注销\","
                                + "\"role\":\"FAMILY\",\"captchaKey\":\"" + captchaKey + "\","
                                + "\"captchaCode\":\"" + captchaCode + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();
        long newUserId = Long.parseLong(
                com.jayway.jsonpath.JsonPath.read(registered.getResponse().getContentAsString(), "$.data.userId")
                        .toString());

        // ② 注册成功后立刻签一张合法令牌（版本读实时值，与登录流程同口径）
        String staleToken = token(newUserId, RoleConstants.FAMILY);
        String versionKey = RedisKeyConstants.passwordVersion(newUserId);
        try {
            mockMvc.perform(get("/api/user/profile").header(AUTH_HEADER, staleToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));

            // ③ 注销成功
            mockMvc.perform(post("/api/user/deactivate")
                            .contentType(JSON)
                            .content("{\"password\":\"Nl@123456\",\"confirmText\":\"我已知晓注销后果\"}")
                            .header(AUTH_HEADER, staleToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.message").value("账号已注销"));

            // ④ 同一张旧令牌再访问 → 401（版本已被抬高，验票不通过）
            mockMvc.perform(get("/api/user/profile").header(AUTH_HEADER, staleToken))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value(401));
        } finally {
            redisTemplate.delete(versionKey);
        }
    }

    /* ============ 工具 ============ */

    private String elder() {
        return token(ELDER_ID, RoleConstants.ELDER);
    }

    /** 签真实 accessToken（密码版本取实时值）；已含 Bearer 前缀 */
    private String token(long userId, String role) {
        return TestTokens.bearer(tokenProvider, tokenStore, userId, role, "user-matrix");
    }
}

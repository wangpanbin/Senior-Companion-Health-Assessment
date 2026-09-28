package org.company.nianglin.security;

import org.company.nianglin.common.ResultCode;
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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
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

    /* ============ 工具 ============ */

    private String elder() {
        return token(ELDER_ID, RoleConstants.ELDER);
    }

    /** 签真实 accessToken（密码版本取实时值）；已含 Bearer 前缀 */
    private String token(long userId, String role) {
        return TestTokens.bearer(tokenProvider, tokenStore, userId, role, "user-matrix");
    }
}

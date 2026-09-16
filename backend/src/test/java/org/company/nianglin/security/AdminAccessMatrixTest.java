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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M9 管理后台越权矩阵：4 角色 × 3 类接口（读列表 / 写操作 / 操作日志）。
 *
 * <p>M9 的鉴权特点：<b>整类 `@PreAuthorize("hasRole('ADMIN')")` 写在类上</b>，
 * 没有任何「部分接口开放给其他角色」的例外。因此本矩阵的核心断言就是
 * 「非管理员一律 403」—— 这是一条靠注解就能保证、但必须被测试锁死的性质：
 * 一旦有人给某个方法加了个 `@PreAuthorize` 覆盖类级注解，这里会立刻变红。</p>
 *
 * <h3>用例只发「必然被拒」的请求，不产生数据副作用</h3>
 *
 * <p>与本项目其它矩阵一致：越权用例全部止步于鉴权阶段（403 / 401），
 * 不会走到 Service，因此可以随时重跑。管理员侧的正面用例只断言
 * 「HTTP 200 且 code 不是 403/401」，不依赖具体种子数据行数。</p>
 *
 * @author 银龄伴诊团队
 * @since M9
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("M9 管理后台越权矩阵：4 角色 × 3 类接口")
class AdminAccessMatrixTest {

    private static final String AUTH_HEADER = "Authorization";
    private static final long ADMIN_ID = 1L;

    private static final org.springframework.http.MediaType JSON =
            org.springframework.http.MediaType.APPLICATION_JSON;

    /** 读类：陪诊员资质审核列表 */
    private static final String API_AUDIT_LIST = "/api/admin/companion/audit";
    /** 写类：封禁用户（POST，必然被非管理员拒绝） */
    private static final String API_DISABLE_USER = "/api/admin/user/9001/disable";
    /** 日志类：操作日志查询 */
    private static final String API_OPER_LOG = "/api/admin/oper-log";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    /** 密码版本必须读实时值 —— 登出会 bump 版本，写死 0 会被 e2e 的历史遗留状态击穿 */
    @Autowired
    private TokenStore tokenStore;

    /* ================================================================== */
    /* 1 · 读类接口：非管理员一律 403                                        */
    /* ================================================================== */

    @ParameterizedTest(name = "审核列表 · {0} → 403")
    @ValueSource(strings = {RoleConstants.ELDER, RoleConstants.FAMILY, RoleConstants.COMPANION})
    @DisplayName("读类 · 非管理员看资质审核列表 → 403")
    void nonAdminShouldNotListAudit(String role) throws Exception {
        mockMvc.perform(get(API_AUDIT_LIST).header(AUTH_HEADER, bearer(userIdOf(role), role)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    @DisplayName("读类 · 未登录看审核列表 → 401")
    void anonymousShouldGet401OnAuditList() throws Exception {
        mockMvc.perform(get(API_AUDIT_LIST))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    /* ================================================================== */
    /* 2 · 写类接口：非管理员一律 403                                        */
    /* ================================================================== */

    @ParameterizedTest(name = "封禁用户 · {0} → 403")
    @ValueSource(strings = {RoleConstants.ELDER, RoleConstants.FAMILY, RoleConstants.COMPANION})
    @DisplayName("写类 · 非管理员封禁用户 → 403（封禁是最敏感的写操作）")
    void nonAdminShouldNotDisableUser(String role) throws Exception {
        // 必须带一个「能通过参数绑定与校验」的请求体。
        // 否则请求会在参数解析阶段就抛 MissingServletRequestPart / HttpMessageNotReadable，
        // 那是<b>早于方法级 @PreAuthorize</b> 的位置 —— 用例会拿到 200（兜底分支统一转成业务体），
        // 于是断言 403 失败，看起来像「鉴权没生效」，实则根本没走到鉴权。
        mockMvc.perform(post(API_DISABLE_USER)
                        .contentType(JSON)
                        .content("{\"reason\":\"多次爽约且未提前告知家属\"}")
                        .header(AUTH_HEADER, bearer(userIdOf(role), role)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    @DisplayName("写类 · 未登录封禁用户 → 401")
    void anonymousShouldGet401OnDisableUser() throws Exception {
        mockMvc.perform(post(API_DISABLE_USER))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    /* ================================================================== */
    /* 3 · 日志类接口：非管理员一律 403                                      */
    /* ================================================================== */

    @ParameterizedTest(name = "操作日志 · {0} → 403")
    @ValueSource(strings = {RoleConstants.ELDER, RoleConstants.FAMILY, RoleConstants.COMPANION})
    @DisplayName("日志类 · 非管理员查操作日志 → 403（日志含全部管理动作，尤其不能外泄）")
    void nonAdminShouldNotReadOperLog(String role) throws Exception {
        mockMvc.perform(get(API_OPER_LOG).header(AUTH_HEADER, bearer(userIdOf(role), role)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    @DisplayName("日志类 · 未登录查操作日志 → 401")
    void anonymousShouldGet401OnOperLog() throws Exception {
        mockMvc.perform(get(API_OPER_LOG))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    /* ================================================================== */
    /* 4 · 管理员侧：注解确实放行（否则上面前提不成立）                        */
    /* ================================================================== */

    @Test
    @DisplayName("正面用例 · 管理员读操作日志 → 非 403/401（注解确实放行）")
    void adminShouldPassAnnotationGate() throws Exception {
        mockMvc.perform(get(API_OPER_LOG).header(AUTH_HEADER, admin()))
                // 只断言「跨过了鉴权闸门」：HTTP 200 且 code 不是 403/401。
                // 不锁具体行数，避免与种子数据耦合导致重跑变脆
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.anyOf(
                                org.hamcrest.Matchers.is(403), org.hamcrest.Matchers.is(401)))));
    }

    /* ================================================================== */

    private String admin() {
        return bearer(ADMIN_ID, RoleConstants.ADMIN);
    }

    /** 给每个角色一个「该角色真实存在」的种子账号 ID，避免用不存在的用户干扰判定 */
    private long userIdOf(String role) {
        return switch (role) {
            case RoleConstants.ELDER -> 201L;
            case RoleConstants.FAMILY -> 101L;
            case RoleConstants.COMPANION -> 301L;
            default -> 1L;
        };
    }

    private String bearer(long userId, String role) {
        return TestTokens.bearer(tokenProvider, tokenStore, userId, role, "m9");
    }
}

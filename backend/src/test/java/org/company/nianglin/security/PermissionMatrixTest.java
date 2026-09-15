package org.company.nianglin.security;

import org.company.nianglin.constant.RoleConstants;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M2 权限矩阵验收：4 角色 × 3 类接口（只读 / 写 / 管理）= 12 条越权用例。
 *
 * <p><b>为什么用真实 JWT 而不是 {@code @WithMockUser}</b>：这里要验证的恰恰是
 * 「令牌 → 过滤器 → SecurityContext → 授权规则」这条完整链路。
 * 若用测试框架直接注入一个 {@code User} 主体，就绕开了自己的
 * {@link JwtAuthenticationFilter}，等于没测。所以这里签真令牌、走真过滤器。</p>
 *
 * <p>探针接口只回显身份不碰数据库，因此用例不产生任何数据副作用。</p>
 *
 * @author 银龄伴诊团队
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("M2 权限矩阵：4 角色 × 3 类接口 = 12 条越权用例")
class PermissionMatrixTest {

    /** 只读类接口：登录即可读 */
    private static final String API_READ = "/api/common/perm-probe/authenticated";

    /** 写类接口：角色门槛只放行 ELDER，用于验证「老人只读」是否真的在服务端生效 */
    private static final String API_WRITE = "/api/common/perm-probe/elder-write";

    /** 管理类接口：仅管理员 */
    private static final String API_ADMIN = "/api/common/perm-probe/admin";

    private static final String AUTH_HEADER = "Authorization";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    /* ================================================================== */
    /* 第 1 类（4 条）：只读接口 —— 四个角色都应放行                          */
    /* ================================================================== */

    @ParameterizedTest(name = "只读接口 · {0} → 200")
    @ValueSource(strings = {RoleConstants.ELDER, RoleConstants.FAMILY, RoleConstants.COMPANION, RoleConstants.ADMIN})
    @DisplayName("只读接口：任意已登录角色均可访问")
    void readEndpointShouldAllowEveryRole(String role) throws Exception {
        mockMvc.perform(get(API_READ).header(AUTH_HEADER, bearer(role)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.role").value(role));
    }

    /* ================================================================== */
    /* 第 2 类（4 条）：写接口 —— 四个角色都应被拒                            */
    /* ================================================================== */

    @ParameterizedTest(name = "写接口 · {0} → 403")
    @ValueSource(strings = {RoleConstants.ELDER, RoleConstants.FAMILY, RoleConstants.COMPANION, RoleConstants.ADMIN})
    @DisplayName("写接口：四个角色均被拒绝（老人被只读规则拦，其余被角色规则拦）")
    void writeEndpointShouldRejectEveryRole(String role) throws Exception {
        mockMvc.perform(post(API_WRITE).header(AUTH_HEADER, bearer(role)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    @DisplayName("写接口 · ELDER：被「只读模式」拦截，而非泛泛的「没有操作权限」")
    void elderWriteShouldBeBlockedByReadOnlyRule() throws Exception {
        // 与上一条的关键区别：这里断言**提示语**。老人账号即使角色匹配，
        // 也必须被只读规则挡下，且提示要能解释原因，否则用户只会看到「没有权限」而困惑
        mockMvc.perform(post(API_WRITE).header(AUTH_HEADER, bearer(RoleConstants.ELDER)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(containsString("只读")));
    }

    /* ================================================================== */
    /* 第 3 类（4 条）：管理接口 —— 仅 ADMIN 放行                            */
    /* ================================================================== */

    @Test
    @DisplayName("管理接口 · ADMIN → 200")
    void adminEndpointShouldAllowAdmin() throws Exception {
        mockMvc.perform(get(API_ADMIN).header(AUTH_HEADER, bearer(RoleConstants.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.role").value(RoleConstants.ADMIN));
    }

    @ParameterizedTest(name = "管理接口 · {0} → 403")
    @ValueSource(strings = {RoleConstants.ELDER, RoleConstants.FAMILY, RoleConstants.COMPANION})
    @DisplayName("管理接口：非管理员一律 403")
    void adminEndpointShouldRejectNonAdmin(String role) throws Exception {
        mockMvc.perform(get(API_ADMIN).header(AUTH_HEADER, bearer(role)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    /* ================================================================== */
    /* 补充：认证层本身                                                      */
    /* ================================================================== */

    @Test
    @DisplayName("不带令牌访问受保护接口 → HTTP 401 且响应体仍是统一结构")
    void missingTokenShouldReturn401() throws Exception {
        mockMvc.perform(get(API_READ))
                .andExpect(status().isUnauthorized())
                // 响应体必须是 { code, message, data }，否则前端拦截器拿到空体无法触发刷新流程
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    @DisplayName("令牌被篡改 → 401（验签失败）")
    void tamperedTokenShouldReturn401() throws Exception {
        String tampered = bearer(RoleConstants.ADMIN) + "x";
        mockMvc.perform(get(API_ADMIN).header(AUTH_HEADER, tampered))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("伪造角色：拿 FAMILY 令牌声明自己是 ADMIN 也会被验签拒绝")
    void forgedRoleClaimShouldBeRejected() throws Exception {
        // 攻击者把载荷里的 role 改成 ADMIN 再重新 base64url 编码，但签名是对原载荷算的，
        // 改一个字节就对不上 —— 这正是 JWT 不需要「加密」也安全的原因
        String token = tokenProvider.createAccessToken(1L, "attacker", RoleConstants.FAMILY, 0);
        String[] parts = token.split("\\.");
        String decodedPayload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        String forgedPayload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                decodedPayload.replace("\"FAMILY\"", "\"ADMIN\"").getBytes(StandardCharsets.UTF_8));
        String forged = parts[0] + "." + forgedPayload + "." + parts[2];

        mockMvc.perform(get(API_ADMIN).header(AUTH_HEADER, "Bearer " + forged))
                .andExpect(status().isUnauthorized());
    }

    /* ================================================================== */

    private String bearer(String role) {
        return "Bearer " + tokenProvider.createAccessToken(1L, "probe-" + role, role, 0);
    }
}

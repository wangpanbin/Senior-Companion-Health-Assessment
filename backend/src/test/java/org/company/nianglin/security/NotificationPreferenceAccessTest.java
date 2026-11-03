package org.company.nianglin.security;

import org.company.nianglin.constant.RoleConstants;
import org.company.nianglin.support.TestTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 通知偏好接口越权与读写测试（V4 · P1-3）。
 *
 * <p>矩阵口径：只有 FAMILY 能读写，其余三个角色（ELDER / COMPANION / ADMIN）一律 403；
 * 未登录 401。正向用例覆盖「无记录返回默认 true → PUT 关闭 → GET 读回 false」。</p>
 *
 * <p>{@code @Transactional} 让 PUT 插入的偏好行在用例结束后回滚，
 * 不会污染种子数据，也不依赖测试执行顺序。</p>
 *
 * @author 银龄伴诊团队
 * @since V4 · P1-3
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@DisplayName("V4 P1-3 通知偏好：FAMILY 可读写，其余角色 403")
class NotificationPreferenceAccessTest {

    private static final String AUTH_HEADER = "Authorization";
    private static final long FAMILY_ID = 101L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private TokenStore tokenStore;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clearSeedPreference() {
        // 物理删除而非逻辑删除：uk_user_id 是普通唯一索引，软删行仍会挡住重新插入。
        // 事务回滚后种子数据原样恢复。
        jdbcTemplate.update("DELETE FROM notification_preference WHERE user_id = ?", FAMILY_ID);
    }

    @Test
    @DisplayName("FAMILY 未设置过 → GET 返回两个 true（GET 不落库）")
    void familyShouldGetDefaults() throws Exception {
        mockMvc.perform(get("/api/message/notification-preference")
                        .header(AUTH_HEADER, bearer(FAMILY_ID, RoleConstants.FAMILY)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.smsMissedEnabled").value(true))
                .andExpect(jsonPath("$.data.quietHoursEnabled").value(true));
    }

    @Test
    @DisplayName("FAMILY PUT 关闭两个开关 → 返回同构结果，再 GET 读回 false")
    void familyShouldUpdateAndReadBack() throws Exception {
        mockMvc.perform(put("/api/message/notification-preference")
                        .header(AUTH_HEADER, bearer(FAMILY_ID, RoleConstants.FAMILY))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"smsMissedEnabled\":false,\"quietHoursEnabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.smsMissedEnabled").value(false))
                .andExpect(jsonPath("$.data.quietHoursEnabled").value(false));

        mockMvc.perform(get("/api/message/notification-preference")
                        .header(AUTH_HEADER, bearer(FAMILY_ID, RoleConstants.FAMILY)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.smsMissedEnabled").value(false))
                .andExpect(jsonPath("$.data.quietHoursEnabled").value(false));
    }

    @Test
    @DisplayName("PUT 缺少 quietHoursEnabled → 400（两个开关都必填）")
    void shouldRejectMissingField() throws Exception {
        mockMvc.perform(put("/api/message/notification-preference")
                        .header(AUTH_HEADER, bearer(FAMILY_ID, RoleConstants.FAMILY))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"smsMissedEnabled\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }

    @ParameterizedTest(name = "GET · {0} → 403")
    @ValueSource(strings = {RoleConstants.ELDER, RoleConstants.COMPANION, RoleConstants.ADMIN})
    @DisplayName("非 FAMILY 读偏好 → 403")
    void nonFamilyShouldGet403OnRead(String role) throws Exception {
        mockMvc.perform(get("/api/message/notification-preference")
                        .header(AUTH_HEADER, bearer(userIdOf(role), role)))
                .andExpect(status().isForbidden());
    }

    @ParameterizedTest(name = "PUT · {0} → 403")
    @ValueSource(strings = {RoleConstants.ELDER, RoleConstants.COMPANION, RoleConstants.ADMIN})
    @DisplayName("非 FAMILY 改偏好 → 403")
    void nonFamilyShouldGet403OnWrite(String role) throws Exception {
        mockMvc.perform(put("/api/message/notification-preference")
                        .header(AUTH_HEADER, bearer(userIdOf(role), role))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"smsMissedEnabled\":false,\"quietHoursEnabled\":false}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("未登录 → 401")
    void anonymousShouldGet401() throws Exception {
        mockMvc.perform(get("/api/message/notification-preference"))
                .andExpect(status().isUnauthorized());
    }

    /* ------------------------------------------------------------------ */

    private String bearer(long userId, String role) {
        return TestTokens.bearer(tokenProvider, tokenStore, userId, role, "notification-preference");
    }

    private static long userIdOf(String role) {
        return switch (role) {
            case RoleConstants.ELDER -> 201L;
            case RoleConstants.COMPANION -> 301L;
            case RoleConstants.ADMIN -> 1L;
            default -> FAMILY_ID;
        };
    }
}

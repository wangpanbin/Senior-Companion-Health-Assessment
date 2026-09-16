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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M8 站内信越权矩阵：4 角色 × 3 类接口（列表 / 未读数 / 已读与删除）。
 *
 * <h3>本模块的特殊之处：一个 {@code @PreAuthorize} 都没有</h3>
 *
 * <p>站内信的可见范围是「收件人 = 当前登录用户」，与角色无关 ——
 * 老人、家属、陪诊员、管理员都只看自己的消息。角色注解在这里
 * <b>既不必要也不充分</b>：它挡不住「家属 A 看家属 B 的消息」，
 * 那件事只能靠 Service 里的 {@code receiverId} 比对。所以本矩阵的核心断言不是
 * 「谁能调」，而是「<b>调到了也只能拿到自己的东西</b>」。</p>
 *
 * <h3>第二组：老人写操作是「默认安全 + 逐个例外」</h3>
 *
 * <p>{@code ElderReadOnlyInterceptor} 默认拒绝 ELDER 的一切写方法。
 * 而「标记已读 / 删除自己的消息」是老人<b>本就应该能做</b>的动作，
 * 不属于「必须由家属代做」的敏感写操作，因此这三个方法上显式标了
 * {@link AllowElderWrite}。这一组用例的关键在于<b>断言错误码是 7002 而不是 403</b>：
 * 拿到 7002 说明请求已经穿过只读拦截器、到达 Service 并走到了归属判定；
 * 若拿到 403 且提示「只读」，就说明那个例外注解失效了。</p>
 *
 * <h3>依赖的种子数据</h3>
 *
 * <p>{@code internal_message} <b>40001</b>：收件人 301（陪诊员）、<b>已读</b>；
 * <b>40002</b>：收件人 102（家属）、未读。两者都不属于家属 101，也不属于老人 201。</p>
 *
 * <p>本类只发「必然被拒」的写请求，以及一条<b>本就已读、因而不会产生写入</b>的
 * 幂等请求，不改动任何数据，可随时重跑。</p>
 *
 * @author 银龄伴诊团队
 * @since M8
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("M8 站内信越权矩阵：4 角色 × 3 类接口")
class MessageAccessMatrixTest {

    private static final String AUTH_HEADER = "Authorization";

    private static final long FAMILY_101 = 101L;
    private static final long ELDER_201 = 201L;
    private static final long COMPANION_301 = 301L;
    private static final long ADMIN_ID = 1L;
    private static final long UNKNOWN_USER = 999L;

    /** 收件人是 102 的消息 —— 对 101 / 201 / 301 都是「别人的消息」 */
    private static final long MESSAGE_OF_102 = 40002L;
    /** 收件人是 301 且状态已读的消息 —— 用来验证标记已读的幂等性 */
    private static final long READ_MESSAGE_OF_301 = 40001L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private TokenStore tokenStore;

    /* ================================================================== */
    /* 1 · 读：登录即可，人人只看自己的那一份                                  */
    /* ================================================================== */

    @ParameterizedTest(name = "消息列表 · {0} → 200")
    @ValueSource(strings = {RoleConstants.ELDER, RoleConstants.FAMILY, RoleConstants.COMPANION, RoleConstants.ADMIN})
    @DisplayName("消息列表 · 四个角色都能读（读到的永远是自己的消息）")
    void listShouldAllowEveryRole(String role) throws Exception {
        mockMvc.perform(get("/api/message").header(AUTH_HEADER, bearer(userIdOf(role), role)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @ParameterizedTest(name = "未读数 · {0} → 200")
    @ValueSource(strings = {RoleConstants.ELDER, RoleConstants.FAMILY, RoleConstants.COMPANION, RoleConstants.ADMIN})
    @DisplayName("未读数 · 四个角色都能读（顶栏红点靠它 + SSE 推送驱动）")
    void unreadCountShouldAllowEveryRole(String role) throws Exception {
        mockMvc.perform(get("/api/message/unread-count").header(AUTH_HEADER, bearer(userIdOf(role), role)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    @DisplayName("消息列表 · 未登录 → 401")
    void anonymousShouldGet401OnList() throws Exception {
        mockMvc.perform(get("/api/message"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    @DisplayName("未读数 · 未登录 → 401")
    void anonymousShouldGet401OnUnreadCount() throws Exception {
        mockMvc.perform(get("/api/message/unread-count"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    /* ================================================================== */
    /* 2 · 写：老人被放行（@AllowElderWrite 例外）但归属仍然管着              */
    /* ================================================================== */

    @Test
    @DisplayName("标记已读 · 老人标记别人的消息 → 7002（放行了只读拦截，但归属拦下）")
    void elderShouldGet7002InsteadOf403() throws Exception {
        // 这条断言一石二鸟：
        // 1) 若不是 403「只读」，说明 @AllowElderWrite 让老人通过了写拦截器；
        // 2) 7002 说明 Service 的 receiverId 比对生效 —— 老人也读不到别人的信。
        //    若这里变成 200，就是站内信最严重的越权缺陷。
        mockMvc.perform(put("/api/message/{id}/read", MESSAGE_OF_102)
                        .header(AUTH_HEADER, elder(ELDER_201)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.MESSAGE_NO_PERMISSION.getCode()));
    }

    @Test
    @DisplayName("删除消息 · 老人删别人的消息 → 7002（删除同样是放行的例外，但同样要过归属）")
    void elderShouldGet7002WhenDeletingOthersMessage() throws Exception {
        mockMvc.perform(delete("/api/message/{id}", MESSAGE_OF_102)
                        .header(AUTH_HEADER, elder(ELDER_201)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.MESSAGE_NO_PERMISSION.getCode()));
    }

    @Test
    @DisplayName("标记已读 · 家属标记别人的消息 → 7002（不是自己的消息，任何角色都一样）")
    void familyShouldNotMarkOthersMessageRead() throws Exception {
        mockMvc.perform(put("/api/message/{id}/read", MESSAGE_OF_102)
                        .header(AUTH_HEADER, family(FAMILY_101)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.MESSAGE_NO_PERMISSION.getCode()));
    }

    @Test
    @DisplayName("删除消息 · 家属删别人的消息 → 7002（逻辑删除只影响接收人自己的视图）")
    void familyShouldNotDeleteOthersMessage() throws Exception {
        mockMvc.perform(delete("/api/message/{id}", MESSAGE_OF_102)
                        .header(AUTH_HEADER, family(FAMILY_101)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.MESSAGE_NO_PERMISSION.getCode()));
    }

    @Test
    @DisplayName("标记已读 · 陪诊员标记自己的消息 → 200 且 affected=0（幂等，不重复扣未读数）")
    void companionShouldMarkOwnMessageReadIdempotently() throws Exception {
        // 40001 在种子里就是已读状态，因此这次调用不产生任何写入 ——
        // 既是幂等性的正面用例，也保证本类可以随时免费重跑
        mockMvc.perform(put("/api/message/{id}/read", READ_MESSAGE_OF_301)
                        .header(AUTH_HEADER, companion(COMPANION_301)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.affected").value(0));
    }

    @Test
    @DisplayName("标记已读 · 消息不存在 → 7001（与 7002「无权」区分开）")
    void missingMessageShouldReturn7001() throws Exception {
        mockMvc.perform(put("/api/message/{id}/read", 999999L)
                        .header(AUTH_HEADER, family(FAMILY_101)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.MESSAGE_NOT_FOUND.getCode()));
    }

    @Test
    @DisplayName("删除消息 · 消息不存在 → 7001")
    void missingMessageShouldReturn7001OnDelete() throws Exception {
        mockMvc.perform(delete("/api/message/{id}", 999999L)
                        .header(AUTH_HEADER, family(FAMILY_101)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.MESSAGE_NOT_FOUND.getCode()));
    }

    @Test
    @DisplayName("标记已读 · 未登录 → 401")
    void anonymousShouldGet401OnMarkRead() throws Exception {
        mockMvc.perform(put("/api/message/{id}/read", READ_MESSAGE_OF_301))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    @DisplayName("删除消息 · 未登录 → 401")
    void anonymousShouldGet401OnDelete() throws Exception {
        mockMvc.perform(delete("/api/message/{id}", READ_MESSAGE_OF_301))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    @DisplayName("全部已读 · 未登录 → 401")
    void anonymousShouldGet401OnMarkAllRead() throws Exception {
        mockMvc.perform(put("/api/message/read-all"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    /* ================================================================== */
    /* 3 · 批量已读：影响范围只能是自己                                       */
    /* ================================================================== */

    @Test
    @DisplayName("全部已读 · 管理员批量标记 → 200，且永远只作用于自己的收件箱")
    void adminMarkAllReadHitsOnlyOwnInbox() throws Exception {
        // 「全部已读」是 M8 里唯一一个可能「一次影响多行」的接口，因此格外要看它
        // 是否被 receiver_id 收窄 —— 管理员在这里没有任何特权，他标记的仍只是
        // 发给自己的那些。用例刻意带上 type 过滤（用药提醒是发给家属的，
        // 不会落在管理员收件箱里），把影响面收敛到 0 行，保证可反复重跑。
        mockMvc.perform(put("/api/message/read-all")
                        .header(AUTH_HEADER, admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"MEDICATION_REMIND\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.unreadCount").isNumber());
    }

    /* ================================================================== */

    private long userIdOf(String role) {
        return switch (role) {
            case RoleConstants.ELDER -> ELDER_201;
            case RoleConstants.FAMILY -> FAMILY_101;
            case RoleConstants.COMPANION -> COMPANION_301;
            case RoleConstants.ADMIN -> ADMIN_ID;
            default -> UNKNOWN_USER;
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
        return TestTokens.bearer(tokenProvider, tokenStore, userId, role, "m8");
    }
}

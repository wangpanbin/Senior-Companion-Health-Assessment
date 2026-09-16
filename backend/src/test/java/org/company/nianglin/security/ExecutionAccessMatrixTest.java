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
 * M5 陪诊执行越权矩阵：4 角色 × 3 类接口（打卡 / 轨迹查询 / 照片上传）。
 *
 * <h3>M5 的鉴权形态与 M4 不同</h3>
 *
 * <ul>
 *   <li><b>写接口（打卡、传照片）</b>：{@code @PreAuthorize("hasRole('COMPANION')")} —— 只有陪诊员能打卡。</li>
 *   <li><b>查询接口（checkins / track / progress）</b>：<b>刻意不加注解</b> —— 家属要看陪诊进度，
 *       陪诊员要回看自己的记录，两者都得放行。谁能读哪一单，由 Service 层的
 *       {@code requireInvolved} 决定。这就使得查询接口的<b>越权防线完全落在归属校验上</b>，
 *       本矩阵第 3 组专门盯这一点。</li>
 * </ul>
 *
 * <h3>依赖的种子数据</h3>
 *
 * <p>订单 1001：家属 101 下单、老人 401、<b>无陪诊员</b>、状态 PENDING。
 * 家属 102 与它无关 —— 正是「令牌合法、角色正确、订单真实存在，只有归属能挡」的理想靶子。</p>
 *
 * <p>本类只发「必然被拒」的写请求与只读请求，不改动任何数据，可随时重跑。</p>
 *
 * @author 银龄伴诊团队
 * @since M5
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("M5 陪诊执行越权矩阵：4 角色 × 3 类接口")
class ExecutionAccessMatrixTest {

    private static final String AUTH_HEADER = "Authorization";
    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;

    /** 靶子订单：家属 101、老人 401、无陪诊员、PENDING */
    private static final long ORDER_OF_101 = 1001L;
    private static final long FAMILY_OWNER = 101L;
    private static final long FAMILY_OTHER = 102L;
    private static final long ELDER_OWNER = 201L;
    private static final long COMPANION_301 = 301L;
    private static final long ADMIN_ID = 1L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private TokenStore tokenStore;

    /* ================================================================== */
    /* 1 · 打卡（写）：仅陪诊员能打卡                                        */
    /* ================================================================== */

    @ParameterizedTest(name = "打卡 · {0} → 403")
    @ValueSource(strings = {RoleConstants.FAMILY, RoleConstants.ELDER, RoleConstants.ADMIN})
    @DisplayName("打卡 · 家属 / 老人 / 管理员打卡 → 403（打卡只能由陪诊员本人做）")
    void nonCompanionShouldNotCheckin(String role) throws Exception {
        mockMvc.perform(post("/api/execution/{orderId}/checkin", ORDER_OF_101)
                        .header(AUTH_HEADER, bearer(userIdOf(role), role))
                        .contentType(JSON)
                        .content(checkinBody()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    @DisplayName("打卡 · 老人打卡被「只读模式」拦下 → 403 且提示解释原因")
    void elderCheckinShouldBeBlockedByReadOnlyRule() throws Exception {
        // 老人即使角色注解能过（实际过不了），也必须被只读规则挡下，提示要能解释原因
        mockMvc.perform(post("/api/execution/{orderId}/checkin", ORDER_OF_101)
                        .header(AUTH_HEADER, bearer(ELDER_OWNER, RoleConstants.ELDER))
                        .contentType(JSON)
                        .content(checkinBody()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.message").value(containsString("只读")));
    }

    @Test
    @DisplayName("打卡 · 未登录 → 401")
    void anonymousShouldGet401OnCheckin() throws Exception {
        mockMvc.perform(post("/api/execution/{orderId}/checkin", ORDER_OF_101)
                        .contentType(JSON).content(checkinBody()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    @DisplayName("打卡 · 陪诊员打卡不属于自己的订单 → 归属校验拦下（非 200 成功）")
    void companionShouldNotCheckinOthersOrder() throws Exception {
        // 1001 还没有陪诊员，301 不是它的接单人。
        // 断言「业务失败」而非具体码：状态/身份两道闸门谁先抛都算正确拦截
        mockMvc.perform(post("/api/execution/{orderId}/checkin", ORDER_OF_101)
                        .header(AUTH_HEADER, companion(COMPANION_301))
                        .contentType(JSON)
                        .content(checkinBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(org.hamcrest.Matchers.not(200)));
    }

    /* ================================================================== */
    /* 2 · 照片上传（写）：同打卡口径                                        */
    /* ================================================================== */

    @ParameterizedTest(name = "传照片 · {0} → 403")
    @ValueSource(strings = {RoleConstants.FAMILY, RoleConstants.ADMIN})
    @DisplayName("传照片 · 非陪诊员上传陪诊照片 → 403")
    void nonCompanionShouldNotUploadPhoto(String role) throws Exception {
        // 文件部分是必须带上的。multipart 请求缺 file 会在<b>参数解析阶段</b>抛
        // MissingServletRequestPartException —— 那个位置早于方法级 @PreAuthorize，
        // 用例会拿到 200（兜底分支把它转成了统一响应体），于是「403 断言失败」看起来像鉴权失效，
        // 其实请求压根没走到鉴权代理。带上一个合法 jpg（magic bytes 正确）才能测到真正的闸门。
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .multipart("/api/execution/{orderId}/photo", ORDER_OF_101)
                        .file(jpeg())
                        .header(AUTH_HEADER, bearer(userIdOf(role), role)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    /**
     * 一个「文件头合法」的 jpg。
     *
     * <p>M5 的上传按 magic bytes（{@code FF D8 FF}）判类型，不信任 Content-Type。
     * 用一个能通过类型校验的文件，是为了确保被拒的理由只能是<b>角色</b>，而不是文件本身 ——
     * 否则用例在「服务端改了校验规则」时会以另一种方式变红，掩盖真正的结论。</p>
     */
    private static org.springframework.mock.web.MockMultipartFile jpeg() {
        return new org.springframework.mock.web.MockMultipartFile("file", "scene.jpg", "image/jpeg",
                new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0x00, 0x10, 'J', 'F', 'I', 'F'});
    }

    /* ================================================================== */
    /* 3 · 查询（读）：不加角色注解，防线全在归属校验上 ← 本类最重要的一组      */
    /* ================================================================== */

    @Test
    @DisplayName("归属 · 下单家属读自己订单的打卡记录 → 200")
    void ownerFamilyShouldReadCheckins() throws Exception {
        mockMvc.perform(get("/api/execution/{orderId}/checkins", ORDER_OF_101)
                        .header(AUTH_HEADER, family(FAMILY_OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    @DisplayName("归属 · 另一个家属读别人的打卡记录 → 3004（查询接口唯一的防线）")
    void otherFamilyShouldNotReadCheckins() throws Exception {
        // 查询接口没有 @PreAuthorize，令牌合法、角色正确、订单真实存在 ——
        // 能挡下来的只有 Service 层归属校验
        mockMvc.perform(get("/api/execution/{orderId}/checkins", ORDER_OF_101)
                        .header(AUTH_HEADER, family(FAMILY_OTHER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.ORDER_NO_PERMISSION.getCode()));
    }

    @Test
    @DisplayName("归属 · 另一个家属读别人的轨迹 → 3004")
    void otherFamilyShouldNotReadTrack() throws Exception {
        mockMvc.perform(get("/api/execution/{orderId}/track", ORDER_OF_101)
                        .header(AUTH_HEADER, family(FAMILY_OTHER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.ORDER_NO_PERMISSION.getCode()));
    }

    @Test
    @DisplayName("归属 · 另一个家属读别人的进度 → 3004")
    void otherFamilyShouldNotReadProgress() throws Exception {
        mockMvc.perform(get("/api/execution/{orderId}/progress", ORDER_OF_101)
                        .header(AUTH_HEADER, family(FAMILY_OTHER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.ORDER_NO_PERMISSION.getCode()));
    }

    @Test
    @DisplayName("归属 · 就诊老人本人读自己的打卡记录 → 200")
    void orderElderShouldReadCheckins() throws Exception {
        mockMvc.perform(get("/api/execution/{orderId}/checkins", ORDER_OF_101)
                        .header(AUTH_HEADER, elder(ELDER_OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    @DisplayName("查询 · 未登录读打卡记录 → 401")
    void anonymousShouldGet401OnCheckins() throws Exception {
        mockMvc.perform(get("/api/execution/{orderId}/checkins", ORDER_OF_101))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    @DisplayName("查询 · 订单不存在 → 3001")
    void missingOrderShouldReturn3001() throws Exception {
        mockMvc.perform(get("/api/execution/{orderId}/progress", 999999L)
                        .header(AUTH_HEADER, family(FAMILY_OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.ORDER_NOT_FOUND.getCode()));
    }

    /* ================================================================== */

    /** 一份格式合法的打卡请求体（本例中它永远到不了业务层） */
    private static String checkinBody() {
        return "{\"node\":\"DEPART\",\"longitude\":110.33119,\"latitude\":20.03197}";
    }

    private long userIdOf(String role) {
        return switch (role) {
            case RoleConstants.ELDER -> ELDER_OWNER;
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

    private String bearer(long userId, String role) {
        return TestTokens.bearer(tokenProvider, tokenStore, userId, role, "m5");
    }
}

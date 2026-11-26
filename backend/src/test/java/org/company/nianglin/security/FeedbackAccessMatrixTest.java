package org.company.nianglin.security;

import org.company.nianglin.support.TestTokens;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 产品反馈的四角色权限矩阵（V6）。
 *
 * <h3>为什么这几条必须走真实 HTTP + 真实令牌（而不是 Service 单测）</h3>
 *
 * <p>反馈的权限由<b>两套机制叠加</b>决定，而它们的先后顺序与返回码完全不同：</p>
 *
 * <ol>
 *   <li><b>角色层</b>：{@code FeedbackController} 类上的
 *       {@code @PreAuthorize("hasAnyRole('FAMILY','COMPANION')")} ——
 *       角色不对直接 HTTP 403，走不到方法体；</li>
 *   <li><b>老人只读层</b>：{@code ElderReadOnlyInterceptor} 对 {@code /api/**} 的非 GET
 *       默认拒绝 ELDER —— 即便将来有人给类注解加上了 ELDER，这一层仍然会拦。</li>
 * </ol>
 *
 * <p>两者都测一遍才叫「双重防线」：只测注解，将来注解被改宽就没人知道拦截器还在不在；
 * 只测拦截器，注解层的语义（这条通道本来就不给老人开）就丢了。</p>
 *
 * <h3>归属层的用例不可省</h3>
 *
 * <p>「另一个家属能不能看别人的反馈」是角色矩阵<b>管不到</b>的那一半 ——
 * 家属 A 与家属 B 角色完全相同。这里断言它拿到的是 {@code 12001} 而不是 {@code 403}，
 * 因为「不存在」与「不是你的」刻意同码（否则可以按 ID 枚举他人反馈是否存在）。</p>
 *
 * @author 银龄伴诊团队
 * @since V6 · 产品反馈
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("产品反馈：角色门槛 / 老人只读 / 归属 12001")
class FeedbackAccessMatrixTest {

    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;

    /** 种子账号：家属 101 / 另一位家属 102 / 陪诊员 307 / 老人 201 / 管理员 1 */
    private static final long FAMILY_ID = 101L;
    private static final long OTHER_FAMILY_ID = 102L;
    private static final long COMPANION_ID = 307L;
    private static final long ELDER_ID = 201L;
    private static final long ADMIN_ID = 1L;

    /** 合法请求体：长度、分类、无敏感词，能跨过校验层直达业务逻辑 */
    private static final String VALID_CREATE_BODY =
            "{\"type\":\"EXPERIENCE\",\"content\":\"订单列表的字有点小，老人模式下也还是偏小。\"}";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private TokenStore tokenStore;

    /** 本类插入的反馈 ID，tearDown 里逐个真删（逻辑删除的痕迹也不留） */
    private final List<Long> createdIds = new java.util.ArrayList<>();

    @BeforeEach
    void setUp() {
        createdIds.clear();
    }

    @AfterEach
    void tearDown() {
        for (Long id : createdIds) {
            jdbc.update("DELETE FROM product_feedback WHERE id = ?", id);
        }
        createdIds.clear();
        // 站内信是终态处理时才发的，本类不走到那里；仍兜底清理一次，
        // 避免将来有人在这里加终态用例后忘记清
        jdbc.update("DELETE FROM internal_message WHERE type = 'FEEDBACK_HANDLED'");
    }

    /* ================================================================== */
    /* 1 · 角色门槛：只有 FAMILY / COMPANION 能进                            */
    /* ================================================================== */

    @ParameterizedTest(name = "提交反馈 · {0} → {1}")
    @CsvSource({"FAMILY,200", "COMPANION,200", "ELDER,403", "ADMIN,403"})
    @DisplayName("提交反馈 · 老人与管理员一律 403（老人只读；管理员的入口在后台处置页）")
    void onlyBusinessRolesCanSubmit(String role, int expectedHttp) throws Exception {
        mvc.perform(post("/api/feedback")
                        .contentType(JSON)
                        .content(VALID_CREATE_BODY)
                        .header("Authorization", bearer(userIdOf(role), role)))
                .andExpect(status().is(expectedHttp));
    }

    @ParameterizedTest(name = "我的反馈 · {0} → {1}")
    @CsvSource({"FAMILY,200", "COMPANION,200", "ELDER,403", "ADMIN,403"})
    @DisplayName("我的反馈列表 · 同样是 FAMILY / COMPANION 专属（管理员走 /api/admin/feedback）")
    void onlyBusinessRolesCanReadMine(String role, int expectedHttp) throws Exception {
        mvc.perform(get("/api/feedback/mine").header("Authorization", bearer(userIdOf(role), role)))
                .andExpect(status().is(expectedHttp));
    }

    @Test
    @DisplayName("未登录提交 → 401（不是 403：这两种在前端的处理路径完全不同）")
    void anonymousShouldGet401() throws Exception {
        mvc.perform(post("/api/feedback").contentType(JSON).content(VALID_CREATE_BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    /* ================================================================== */
    /* 2 · 提交成功后落库、能被本人查到                                      */
    /* ================================================================== */

    @Test
    @DisplayName("家属提交后能在「我的反馈」里看到，且初始状态为待处理")
    void submittedFeedbackShouldAppearInMine() throws Exception {
        mvc.perform(post("/api/feedback")
                        .contentType(JSON)
                        .content(VALID_CREATE_BODY)
                        .header("Authorization", bearer(FAMILY_ID, "FAMILY")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.status").value("PENDING"));

        Long id = jdbc.queryForObject(
                "SELECT id FROM product_feedback WHERE submitter_id = ? ORDER BY id DESC LIMIT 1",
                Long.class, FAMILY_ID);
        createdIds.add(id);

        mvc.perform(get("/api/feedback/mine").header("Authorization", bearer(FAMILY_ID, "FAMILY")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[0].id").value(id))
                // 提交人姓名脱敏：即便本人看自己，也走同一份 VO（一处分叉就是一次泄露机会）
                .andExpect(jsonPath("$.data.records[0].submitterName").exists())
                // 未终态不下发处理说明
                .andExpect(jsonPath("$.data.records[0].handleResult").doesNotExist());
    }

    /* ================================================================== */
    /* 3 · 参数层：非法分类 / 敏感词 / 截图超量                               */
    /* ================================================================== */

    @Test
    @DisplayName("分类非法 → 400（不静默落到 OTHER，否则分类统计失去意义）")
    void illegalTypeShouldBe400() throws Exception {
        mvc.perform(post("/api/feedback")
                        .contentType(JSON)
                        .content("{\"type\":\"PRODUCT\",\"content\":\"希望支持一次给两位老人下单。\"}")
                        .header("Authorization", bearer(FAMILY_ID, "FAMILY")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @DisplayName("正文不足 10 字 → 400（Bean Validation 在参数层就拦下）")
    void tooShortContentShouldBe400() throws Exception {
        mvc.perform(post("/api/feedback")
                        .contentType(JSON)
                        .content("{\"type\":\"OTHER\",\"content\":\"字太小\"}")
                        .header("Authorization", bearer(FAMILY_ID, "FAMILY")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @DisplayName("正文命中敏感词 → 6003，且不落库")
    void sensitiveContentShouldBe6003() throws Exception {
        long before = countFeedback();
        mvc.perform(post("/api/feedback")
                        .contentType(JSON)
                        .content("{\"type\":\"OTHER\",\"content\":\"这个垃圾系统我真是服了，滚吧。\"}")
                        .header("Authorization", bearer(FAMILY_ID, "FAMILY")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(6003));
        org.junit.jupiter.api.Assertions.assertEquals(before, countFeedback(),
                "命中敏感词的提交不能留下任何行");
    }

    @Test
    @DisplayName("截图超过 3 张 → 400")
    void tooManyImagesShouldBe400() throws Exception {
        String body = "{\"type\":\"DEFECT\",\"content\":\"提交之后页面卡住，返回才看到已提交。\","
                + "\"images\":[\"/uploads/202611/1.png\",\"/uploads/202611/2.png\","
                + "\"/uploads/202611/3.png\",\"/uploads/202611/4.png\"]}";
        mvc.perform(post("/api/feedback")
                        .contentType(JSON)
                        .content(body)
                        .header("Authorization", bearer(FAMILY_ID, "FAMILY")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }

    /* ================================================================== */
    /* 4 · 归属：12001 同时覆盖「不存在」与「不是你的」                       */
    /* ================================================================== */

    @Test
    @DisplayName("详情 · 本人可看自己的反馈")
    void ownerCanReadOwnFeedback() throws Exception {
        Long id = insertFeedback(FAMILY_ID);
        mvc.perform(get("/api/feedback/{id}", id).header("Authorization", bearer(FAMILY_ID, "FAMILY")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").value(id));
    }

    @Test
    @DisplayName("详情 · 另一位家属看别人的反馈 → 12001（与“不存在”同码，不给枚举探针）")
    void otherFamilyShouldGet12001() throws Exception {
        Long id = insertFeedback(FAMILY_ID);
        mvc.perform(get("/api/feedback/{id}", id)
                        .header("Authorization", bearer(OTHER_FAMILY_ID, "FAMILY")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(12001));
    }

    @Test
    @DisplayName("详情 · 管理员可看任意反馈（12001 是唯一的失败码，越权不单独开码）")
    void adminCanReadAnyFeedback() throws Exception {
        Long id = insertFeedback(FAMILY_ID);
        mvc.perform(get("/api/feedback/{id}", id).header("Authorization", bearer(ADMIN_ID, "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    @DisplayName("详情 · 不存在的 ID → 12001")
    void missingFeedbackShouldBe12001() throws Exception {
        mvc.perform(get("/api/feedback/{id}", 99_999_999L)
                        .header("Authorization", bearer(FAMILY_ID, "FAMILY")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(12001));
    }

    /* ================================================================== */
    /* 夹具                                                                */
    /* ================================================================== */

    /** 直接落库一条反馈（绕过接口，供只读类用例使用） */
    private Long insertFeedback(long submitterId) {
        jdbc.update("""
                INSERT INTO product_feedback
                    (submitter_id, submitter_role, type, content, status, create_time, update_time, deleted)
                VALUES (?, 'FAMILY', 'EXPERIENCE', '权限矩阵夹具：这行由 FeedbackAccessMatrixTest 写入。',
                        'PENDING', NOW(), NOW(), 0)
                """, submitterId);
        Long id = jdbc.queryForObject(
                "SELECT id FROM product_feedback WHERE submitter_id = ? ORDER BY id DESC LIMIT 1",
                Long.class, submitterId);
        createdIds.add(id);
        return id;
    }

    private long countFeedback() {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM product_feedback", Long.class);
        return n == null ? 0L : n;
    }

    private long userIdOf(String role) {
        return switch (role) {
            case "ELDER" -> ELDER_ID;
            case "FAMILY" -> FAMILY_ID;
            case "COMPANION" -> COMPANION_ID;
            default -> ADMIN_ID;
        };
    }

    private String bearer(long userId, String role) {
        return TestTokens.bearer(tokenProvider, tokenStore, userId, role, "feedback");
    }
}

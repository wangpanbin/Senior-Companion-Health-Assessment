package org.company.nianglin.security;

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
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M9 管理后台越权矩阵：4 角色 × 4 类接口（读列表 / 写操作 / 操作日志 / 履约风险中心）。
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
@DisplayName("M9 管理后台越权矩阵：4 角色 × 4 类接口")
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
    /** 履约风险中心 · 计数（V3.1 · T6） */
    private static final String API_FULFILLMENT_SUMMARY = "/api/admin/fulfillment/summary";
    /** 履约风险中心 · 风险订单列表（V3.1 · T6） */
    private static final String API_FULFILLMENT_RISK_LIST = "/api/admin/fulfillment/risk-list";
    /** 改派陪诊员（V3.1 · T7） */
    private static final String API_REASSIGN = "/api/admin/order/1001/reassign";
    /** 结算核对列表（V4 · P2-1） */
    private static final String API_SETTLEMENT_LIST = "/api/admin/settlement";
    /** 单条结算核定（V4 · P2-1） */
    private static final String API_SETTLEMENT_CONFIRM = "/api/admin/settlement/1001/confirm";
    /** 批量结算核定（V4 · P2-1） */
    private static final String API_SETTLEMENT_BATCH = "/api/admin/settlement/batch-confirm";
    /**
     * 结算单导出（V4 · P2-1）。
     *
     * <p>路径不在 {@code /api/admin} 下，靠 {@code SecurityConfig} 里
     * 新增的 {@code /api/settlement/**} 规则在过滤链上收口 ——
     * 这条用例就是那条规则的回归防线。</p>
     */
    private static final String API_SETTLEMENT_EXPORT = "/api/settlement/export";
    /** 批量核定的合法请求体：越权用例必须能通过参数校验，否则会得到「参数层假绿」 */
    private static final String VALID_BATCH_CONFIRM_BODY = "{\"orderIds\":[1001]}";

    /** 运营参数列表（V4 · P2-3） */
    private static final String API_OPERATION_PARAM = "/api/admin/operation-param";
    /**
     * 评价巡查列表（E4）。
     *
     * <p>路径与 {@code /api/admin/review/{id}/validity}（裁定写操作）同在
     * {@code /api/admin} 下，靠类级 {@code @PreAuthorize("hasRole('ADMIN')")} 收口。
     * 两条必须分开测：一处只读一处写，而「巡检页能看」不蕴含「巡检页能裁」。</p>
     */
    private static final String API_REVIEW_AUDIT_LIST = "/api/admin/review";
    /** 运营参数修改：用真实字典键，避免「键不存在」把越权用例变成 400 假绿 */
    private static final String API_OPERATION_PARAM_UPDATE =
            "/api/admin/operation-param/medication.missed-threshold-minutes";
    /** 合法请求体：非管理员必须在鉴权层被拒，不能死在参数校验层 */
    private static final String VALID_OPERATION_PARAM_BODY = "{\"value\":60,\"remark\":\"越权用例\"}";

    /* V6 · 产品反馈 */
    /** 管理端产品反馈列表（读） */
    private static final String API_FEEDBACK_LIST = "/api/admin/feedback";
    /** 处理产品反馈（写） */
    private static final String API_FEEDBACK_HANDLE = "/api/admin/feedback/5001/handle";
    /** 合法的处理请求体：同「改派」「封禁」同法，让越权用例跳过参数层 */
    private static final String VALID_FEEDBACK_HANDLE_BODY =
            "{\"status\":\"PROCESSING\",\"handleResult\":\"已接手，正在核实是哪一页的字号问题\"}";

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
    /* 4 · 履约风险中心（V3.1 · T6）：非管理员一律 403                     */
    /* ================================================================== */

    @ParameterizedTest(name = "风险计数 · {0} → 403")
    @ValueSource(strings = {RoleConstants.ELDER, RoleConstants.FAMILY, RoleConstants.COMPANION})
    @DisplayName("履约风险中心 · 非管理员看风险计数 → 403（四档数字暴露平台的单量）")
    void nonAdminShouldNotReadFulfillmentSummary(String role) throws Exception {
        mockMvc.perform(get(API_FULFILLMENT_SUMMARY).header(AUTH_HEADER, bearer(userIdOf(role), role)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @ParameterizedTest(name = "风险列表 · {0} → 403")
    @ValueSource(strings = {RoleConstants.ELDER, RoleConstants.FAMILY, RoleConstants.COMPANION})
    @DisplayName("履约风险中心 · 非管理员看风险列表 → 403（列表含脱敏姓名与订单信息）")
    void nonAdminShouldNotReadFulfillmentRiskList(String role) throws Exception {
        mockMvc.perform(get(API_FULFILLMENT_RISK_LIST).header(AUTH_HEADER, bearer(userIdOf(role), role)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    @DisplayName("履约风险中心 · 未登录读风险计数 → 401")
    void anonymousShouldGet401OnFulfillmentSummary() throws Exception {
        mockMvc.perform(get(API_FULFILLMENT_SUMMARY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    @DisplayName("履约风险中心 · 管理员读风险计数 → 四个字段恒定齐全且为数字")
    void adminShouldReadFulfillmentSummary() throws Exception {
        // 这条同时是「SQL 能在真库上跑通」的验收：分组计数里写死的
        // status / fulfillment_risk / deleted 任何一列名拼错，这里就会红
        mockMvc.perform(get(API_FULFILLMENT_SUMMARY).header(AUTH_HEADER, admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.manualRequired").isNumber())
                .andExpect(jsonPath("$.data.urgent").isNumber())
                .andExpect(jsonPath("$.data.warning").isNumber())
                .andExpect(jsonPath("$.data.normal").isNumber());
    }

    @Test
    @DisplayName("履约风险中心 · 管理员读风险列表 → 200 且 records 为空数组也不报错")
    void adminShouldReadFulfillmentRiskList() throws Exception {
        // 整段 ORDER BY 走 last() + 分页拦截器的组合由本用例在真库上验一次：
        // 分页统计要能把 ORDER BY 剥掉、主查询要能拼上 LIMIT
        mockMvc.perform(get(API_FULFILLMENT_RISK_LIST).header(AUTH_HEADER, admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records").isArray());
    }

    @Test
    @DisplayName("履约风险中心 · 管理员传非法风险等级 → 400 且不静默返回全量")
    void adminShouldGet400OnUnknownRiskLevel() throws Exception {
        mockMvc.perform(get(API_FULFILLMENT_RISK_LIST).header(AUTH_HEADER, admin())
                        .param("risk", "AT_RISK"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }

    /* ================================================================== */
    /* 5 · 改派（V3.1 · T7）：非管理员一律 403                             */
    /* ================================================================== */

    /**
     * 改派端点的越权用例必须携带<b>能通过参数校验</b>的请求体。
     *
     * <p>理由与「封禁用户」那组相同：请求体不合法会死在参数解析层，
     * 拿到「因为 body 非法所以 400」的假绿，而真正的越权路径根本没被走到。
     * 这里给出合规的目标陪诊员 ID 与 10–200 字理由。</p>
     */
    private static final String VALID_REASSIGN_BODY =
            "{\"companionId\":302,\"reason\":\"原陪诊员临时有事无法前往，已协调另一位陪诊员接单\"}";

    @ParameterizedTest(name = "改派 · {0} → 403")
    @ValueSource(strings = {RoleConstants.ELDER, RoleConstants.FAMILY, RoleConstants.COMPANION})
    @DisplayName("改派 · 非管理员改派订单 → 403（携带合法请求体，避免参数层假绿）")
    void nonAdminShouldNotReassign(String role) throws Exception {
        mockMvc.perform(post(API_REASSIGN)
                        .contentType(JSON)
                        .content(VALID_REASSIGN_BODY)
                        .header(AUTH_HEADER, bearer(userIdOf(role), role)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    @DisplayName("改派 · 未登录改派订单 → 401")
    void anonymousShouldGet401OnReassign() throws Exception {
        mockMvc.perform(post(API_REASSIGN)
                        .contentType(JSON)
                        .content(VALID_REASSIGN_BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    /* ================================================================== */
    /* 7 · 结算核对（V4 · P2-1）：非管理员一律 403                          */
    /* ================================================================== */

    @ParameterizedTest(name = "结算列表 · {0} → 403")
    @ValueSource(strings = {RoleConstants.ELDER, RoleConstants.FAMILY, RoleConstants.COMPANION})
    @DisplayName("结算 · 非管理员看结算台账 → 403（台账含全平台金额与脱敏姓名）")
    void nonAdminShouldNotReadSettlementList(String role) throws Exception {
        mockMvc.perform(get(API_SETTLEMENT_LIST).header(AUTH_HEADER, bearer(userIdOf(role), role)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @ParameterizedTest(name = "单条核定 · {0} → 403")
    @ValueSource(strings = {RoleConstants.ELDER, RoleConstants.FAMILY, RoleConstants.COMPANION})
    @DisplayName("结算 · 非管理员核定单条 → 403（改记账状态是最敏感的写操作）")
    void nonAdminShouldNotConfirmSettlement(String role) throws Exception {
        mockMvc.perform(post(API_SETTLEMENT_CONFIRM).header(AUTH_HEADER, bearer(userIdOf(role), role)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @ParameterizedTest(name = "批量核定 · {0} → 403")
    @ValueSource(strings = {RoleConstants.ELDER, RoleConstants.FAMILY, RoleConstants.COMPANION})
    @DisplayName("结算 · 非管理员批量核定 → 403（携带合法请求体，避免参数层假绿）")
    void nonAdminShouldNotBatchConfirmSettlement(String role) throws Exception {
        mockMvc.perform(post(API_SETTLEMENT_BATCH)
                        .contentType(JSON)
                        .content(VALID_BATCH_CONFIRM_BODY)
                        .header(AUTH_HEADER, bearer(userIdOf(role), role)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @ParameterizedTest(name = "结算导出 · {0} → 403")
    @ValueSource(strings = {RoleConstants.ELDER, RoleConstants.FAMILY, RoleConstants.COMPANION})
    @DisplayName("结算 · 非管理员导出结算单 → 403（路径不在 /api/admin 下，靠 /api/settlement 规则收口）")
    void nonAdminShouldNotExportSettlement(String role) throws Exception {
        mockMvc.perform(get(API_SETTLEMENT_EXPORT).header(AUTH_HEADER, bearer(userIdOf(role), role)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    @DisplayName("结算 · 未登录核定 → 401")
    void anonymousShouldGet401OnConfirmSettlement() throws Exception {
        mockMvc.perform(post(API_SETTLEMENT_CONFIRM))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    @DisplayName("结算 · 管理员读台账 → 200 且 records 为数组")
    void adminShouldReadSettlementList() throws Exception {
        mockMvc.perform(get(API_SETTLEMENT_LIST).header(AUTH_HEADER, admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records").isArray());
    }

    /* ================================================================== */
    /* 8 · 运营参数（V4 · P2-3）：非管理员一律 403；管理员只读正面用例          */
    /* ================================================================== */

    @ParameterizedTest(name = "参数列表 · {0} → 403")
    @ValueSource(strings = {RoleConstants.ELDER, RoleConstants.FAMILY, RoleConstants.COMPANION})
    @DisplayName("运营参数 · 非管理员读参数列表 → 403（阈值属于平台运营口径）")
    void nonAdminShouldNotReadOperationParams(String role) throws Exception {
        mockMvc.perform(get(API_OPERATION_PARAM).header(AUTH_HEADER, bearer(userIdOf(role), role)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @ParameterizedTest(name = "修改参数 · {0} → 403")
    @ValueSource(strings = {RoleConstants.ELDER, RoleConstants.FAMILY, RoleConstants.COMPANION})
    @DisplayName("运营参数 · 非管理员修改参数 → 403（携带合法请求体，避免参数层假绿）")
    void nonAdminShouldNotUpdateOperationParam(String role) throws Exception {
        mockMvc.perform(put(API_OPERATION_PARAM_UPDATE)
                        .contentType(JSON)
                        .content(VALID_OPERATION_PARAM_BODY)
                        .header(AUTH_HEADER, bearer(userIdOf(role), role)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    @DisplayName("运营参数 · 未登录读列表 → 401")
    void anonymousShouldGet401OnOperationParams() throws Exception {
        mockMvc.perform(get(API_OPERATION_PARAM))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    @DisplayName("运营参数 · 未登录修改 → 401")
    void anonymousShouldGet401OnOperationParamUpdate() throws Exception {
        mockMvc.perform(put(API_OPERATION_PARAM_UPDATE)
                        .contentType(JSON)
                        .content(VALID_OPERATION_PARAM_BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    @DisplayName("运营参数 · 管理员读列表 → 200 且四个参数齐全")
    void adminShouldReadOperationParams() throws Exception {
        // 同时验证 V10 种子确实落库：读不到 OPS_PARAM 时这里会红
        mockMvc.perform(get(API_OPERATION_PARAM).header(AUTH_HEADER, admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(4))
                .andExpect(jsonPath("$.data[0].code").value("medication.missed-threshold-minutes"))
                // status=1 且不是回落默认值，说明 V10 种子确实落库
                .andExpect(jsonPath("$.data[0].status").value(1))
                .andExpect(jsonPath("$.data[0].usingDefault").value(false));
    }

    @Test
    @DisplayName("运营参数 · 管理员改越界值 → 400 且不落库（服务端范围校验前置）")
    void adminShouldGet400OnOutOfRangeOperationParam() throws Exception {
        mockMvc.perform(put(API_OPERATION_PARAM_UPDATE)
                        .contentType(JSON)
                        .content("{\"value\":0}")
                        .header(AUTH_HEADER, admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }

    /* ================================================================== */
    /* 9 · 评价巡查列表（E4）：非管理员一律 403                              */
    /* ================================================================== */

    @ParameterizedTest(name = "评价巡查 · {0} → 403")
    @ValueSource(strings = {RoleConstants.ELDER, RoleConstants.FAMILY, RoleConstants.COMPANION})
    @DisplayName("评价巡查 · 非管理员看巡查列表 → 403（列表含已被裁定无效的评价与脱敏姓名）")
    void nonAdminShouldNotReadReviewAuditList(String role) throws Exception {
        mockMvc.perform(get(API_REVIEW_AUDIT_LIST).header(AUTH_HEADER, bearer(userIdOf(role), role)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    @DisplayName("评价巡查 · 未登录看巡查列表 → 401")
    void anonymousShouldGet401OnReviewAuditList() throws Exception {
        mockMvc.perform(get(API_REVIEW_AUDIT_LIST))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    @DisplayName("评价巡查 · 管理员读巡查列表 → 200 且 records 为数组")
    void adminShouldReadReviewAuditList() throws Exception {
        // 同时是「SQL 能在真库上跑通」的验收：is_valid 三态拼接、
        // 姓名批量取回、标签 JSON 解析任一处出错这里就会红
        mockMvc.perform(get(API_REVIEW_AUDIT_LIST).header(AUTH_HEADER, admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records").isArray());
    }

    @ParameterizedTest(name = "评价巡查评分越界 · minScore={0} maxScore={1} → 400")
    @CsvSource({
            "0, ''",
            "6, ''",
            "'', 0",
            "'', 6",
    })
    @DisplayName("评价巡查 · 评分筛选取值越界 → 400（契约 §29 错误场景）")
    void adminShouldGet400OnOutOfRangeScore(String minScore, String maxScore) throws Exception {
        var request = get(API_REVIEW_AUDIT_LIST).header(AUTH_HEADER, admin());
        if (!minScore.isEmpty()) {
            request = request.param("minScore", minScore);
        }
        if (!maxScore.isEmpty()) {
            request = request.param("maxScore", maxScore);
        }
        // 统一响应结构：HTTP 200 + code=400（见 docs/api/README.md §三）
        mockMvc.perform(request)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }

    /* ================================================================== */
    /* 10 · 产品反馈（V6）：非管理员一律 403                                  */
    /* ================================================================== */

    @ParameterizedTest(name = "产品反馈列表 · {0} → 403")
    @ValueSource(strings = {RoleConstants.ELDER, RoleConstants.FAMILY, RoleConstants.COMPANION})
    @DisplayName("产品反馈 · 非管理员看全量反馈列表 → 403（列表含全部用户的脱敏姓名与建议内容）")
    void nonAdminShouldNotReadFeedbackList(String role) throws Exception {
        mockMvc.perform(get(API_FEEDBACK_LIST).header(AUTH_HEADER, bearer(userIdOf(role), role)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @ParameterizedTest(name = "处理产品反馈 · {0} → 403")
    @ValueSource(strings = {RoleConstants.ELDER, RoleConstants.FAMILY, RoleConstants.COMPANION})
    @DisplayName("产品反馈 · 非管理员处理反馈 → 403（携带合法请求体，避免参数层假绿）")
    void nonAdminShouldNotHandleFeedback(String role) throws Exception {
        mockMvc.perform(post(API_FEEDBACK_HANDLE)
                        .contentType(JSON)
                        .content(VALID_FEEDBACK_HANDLE_BODY)
                        .header(AUTH_HEADER, bearer(userIdOf(role), role)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    @DisplayName("产品反馈 · 未登录看列表 → 401")
    void anonymousShouldGet401OnFeedbackList() throws Exception {
        mockMvc.perform(get(API_FEEDBACK_LIST))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    @DisplayName("产品反馈 · 管理员读列表 → 200 且 records 为数组（SQL 能在真库上跑通）")
    void adminShouldReadFeedbackList() throws Exception {
        // 同时是「SQL 能在真库上跑通」的验收：状态权重排序、姓名批量取回、
        // 截图 JSON 解析、日期区间拼接任一处出错这里就会红
        mockMvc.perform(get(API_FEEDBACK_LIST).header(AUTH_HEADER, admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records").isArray());
    }

    @Test
    @DisplayName("产品反馈 · 管理员处理不存在的反馈 → 12001（不是 403，也不是 500）")
    void adminHandleMissingFeedbackShouldBe12001() throws Exception {
        mockMvc.perform(post(API_FEEDBACK_HANDLE)
                        .contentType(JSON)
                        .content(VALID_FEEDBACK_HANDLE_BODY)
                        .header(AUTH_HEADER, admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(12001));
    }

    /* ================================================================== */
    /* 6 · 管理员侧：注解确实放行（否则上面前提不成立）                        */
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

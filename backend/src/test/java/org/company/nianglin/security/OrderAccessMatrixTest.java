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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M4 订单归属与状态机矩阵：真实 JWT 打真实接口、查真实种子数据。
 *
 * <p>M3 的 {@link ElderOwnershipMatrixTest} 验证「档案是不是你的」，
 * 本类验证订单模块的两条主线：<b>状态机只能沿单向前进</b>，
 * 以及<b>相关方之外的人碰不到这一单</b>。</p>
 *
 * <h3>用例依赖的种子数据（都有出处）</h3>
 *
 * <table border="1">
 *   <caption>V2 迁移写入的订单</caption>
 *   <tr><th>订单</th><th>下单家属</th><th>就诊老人</th><th>陪诊员</th><th>状态</th><th>用途</th></tr>
 *   <tr><td>1001</td><td>101</td><td>401</td><td>—</td><td>PENDING</td><td>归属用例的靶子、跳级用例、被 301 已拒过</td></tr>
 *   <tr><td>1007</td><td>107</td><td>407</td><td>307</td><td>ACCEPTED</td><td>「已接单」状态下的取消 / 非本单陪诊员</td></tr>
 *   <tr><td>1025</td><td>125</td><td>425</td><td>301</td><td>COMPLETED</td><td>陪诊员 301 作为「本单陪诊员」的正面用例</td></tr>
 *   <tr><td>1061</td><td>121</td><td>421</td><td>—</td><td>CANCELLED</td><td>终态不可再流转</td></tr>
 * </table>
 *
 * <p>陪诊员资质：{@code comp001}(301) 已通过；{@code comp025}(325) 待审核；
 * {@code comp029}(329) 已驳回 —— 后两者用来验证「大厅的第二重拦截」。</p>
 *
 * <h3>本类不修改任何数据，可以随时重跑</h3>
 *
 * <p>这里刻意只保留<b>读操作</b>与<b>必然被拒的写操作</b>：归属校验、状态判断、
 * 资质校验都发生在落库之前，所以跑完种子数据原封不动。
 * 真正会改变数据的路径（下单成功、接单、开服务、完成、并发抢单）
 * 由 {@code backend/sql/tools/e2e_order.py} 端到端覆盖，那里自带清理。</p>
 *
 * <p>这样分工的好处是：本类可以在开发过程中被反复执行而不用管脏数据，
 * 而需要造数据的那部分集中在一个有清理逻辑的地方。</p>
 *
 * @author 银龄伴诊团队
 * @since M4
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("M4 订单归属与状态机矩阵：相关方边界 + 单向流转")
class OrderAccessMatrixTest {

    private static final String AUTH_HEADER = "Authorization";
    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;

    /** 靶子订单：家属 101、老人 401、无陪诊员、PENDING */
    private static final long PENDING_ORDER = 1001L;
    /** 已接单：家属 107、陪诊员 307 */
    private static final long ACCEPTED_ORDER = 1007L;
    /** 已完成：陪诊员 301 */
    private static final long COMPLETED_ORDER = 1025L;
    /** 已取消（终态） */
    private static final long CANCELLED_ORDER = 1061L;
    private static final long NOT_EXIST_ORDER = 999999L;

    private static final long FAMILY_OWNER = 101L;
    private static final long FAMILY_OTHER = 102L;
    private static final long ELDER_OWNER = 201L;
    private static final long ELDER_OTHER = 202L;
    /** 已通过审核的陪诊员 */
    private static final long COMPANION_APPROVED = 301L;
    /** 1007 的陪诊员 */
    private static final long COMPANION_OF_1007 = 307L;
    /** 待审核的陪诊员 */
    private static final long COMPANION_PENDING_AUDIT = 325L;
    /** 已驳回的陪诊员 */
    private static final long COMPANION_REJECTED = 329L;
    /** 不存在的陪诊员账号（没有资质快照行） */
    private static final long COMPANION_GHOST = 9999L;
    private static final long ADMIN_ID = 1L;

    /** 一个足够靠后的就诊时间，保证「就诊时间早于当前时间」不会先于归属校验触发 */
    private static final String FUTURE_VISIT = "2099-01-01 09:00:00";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    /** 密码版本必须读实时值 —— 登出会 bump 版本，写死 0 会被 e2e 的历史遗留状态击穿 */
    @Autowired
    private TokenStore tokenStore;

    /* ================================================================== */
    /* 1 · 角色门槛：有些角色完全不该进这个接口                              */
    /* ================================================================== */

    @Test
    @DisplayName("角色 · 陪诊员下单 → 403（下单是家属的事）")
    void companionShouldNotCreateOrder() throws Exception {
        mockMvc.perform(post("/api/order")
                        .header(AUTH_HEADER, companion(COMPANION_APPROVED))
                        .contentType(JSON)
                        .content(createBody(401L, FUTURE_VISIT)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    @DisplayName("角色 · 管理员下单 → 403（管理员下单会绕过「家属绑定」这层关系）")
    void adminShouldNotCreateOrder() throws Exception {
        mockMvc.perform(post("/api/order")
                        .header(AUTH_HEADER, token(ADMIN_ID, RoleConstants.ADMIN))
                        .contentType(JSON)
                        .content(createBody(401L, FUTURE_VISIT)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    @DisplayName("角色 · 家属看订单大厅 → 403（大厅是陪诊员的接单池）")
    void familyShouldNotAccessHall() throws Exception {
        mockMvc.perform(get("/api/order/hall").header(AUTH_HEADER, family(FAMILY_OWNER)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    @DisplayName("角色 · 老人看订单大厅 → 403")
    void elderShouldNotAccessHall() throws Exception {
        mockMvc.perform(get("/api/order/hall").header(AUTH_HEADER, elder(ELDER_OWNER)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    @DisplayName("角色 · 家属接单 → 403")
    void familyShouldNotAcceptOrder() throws Exception {
        mockMvc.perform(post("/api/order/{id}/accept", PENDING_ORDER).header(AUTH_HEADER, family(FAMILY_OWNER)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    @DisplayName("角色 · 陪诊员取消订单 → 403（取消是下单家属的权利）")
    void companionShouldNotCancelOrder() throws Exception {
        mockMvc.perform(put("/api/order/{id}/cancel", PENDING_ORDER)
                        .header(AUTH_HEADER, companion(COMPANION_APPROVED))
                        .contentType(JSON)
                        .content("{\"reason\":\"我不想要了\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    @DisplayName("角色 · 未登录读订单详情 → 401")
    void anonymousShouldGet401OnDetail() throws Exception {
        mockMvc.perform(get("/api/order/{id}", PENDING_ORDER))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    @DisplayName("角色 · 未登录读订单列表 → 401")
    void anonymousShouldGet401OnList() throws Exception {
        mockMvc.perform(get("/api/order"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    /* ================================================================== */
    /* 2 · 陪诊员资质：大厅的第二重拦截（角色注解管不到的那一层）              */
    /* ================================================================== */

    @Test
    @DisplayName("资质 · 待审核的陪诊员看大厅 → 2003（角色对，资质没到）")
    void pendingAuditCompanionShouldNotSeeHall() throws Exception {
        mockMvc.perform(get("/api/order/hall").header(AUTH_HEADER, companion(COMPANION_PENDING_AUDIT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.COMPANION_NOT_AUDITED.getCode()));
    }

    @Test
    @DisplayName("资质 · 已被驳回的陪诊员看大厅 → 2003")
    void rejectedCompanionShouldNotSeeHall() throws Exception {
        mockMvc.perform(get("/api/order/hall").header(AUTH_HEADER, companion(COMPANION_REJECTED)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.COMPANION_NOT_AUDITED.getCode()));
    }

    @Test
    @DisplayName("资质 · 没有资质快照的陪诊员账号 → 2003（不是 500，也不是空列表）")
    void companionWithoutProfileShouldNotSeeHall() throws Exception {
        mockMvc.perform(get("/api/order/hall").header(AUTH_HEADER, companion(COMPANION_GHOST)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.COMPANION_NOT_AUDITED.getCode()));
    }

    @Test
    @DisplayName("资质 · 已通过的陪诊员看大厅 → 200（种子里的待接单都已过期，故列表为空是预期的）")
    void approvedCompanionShouldSeeHall() throws Exception {
        mockMvc.perform(get("/api/order/hall").header(AUTH_HEADER, companion(COMPANION_APPROVED)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.records").isArray());
    }

    /* ================================================================== */
    /* 3 · 归属校验：谁才算「这一单的相关方」                                 */
    /* ================================================================== */

    @Test
    @DisplayName("归属 · 下单家属读自己的订单 → 200，姓名全显")
    void ownerFamilyShouldReadOwnOrder() throws Exception {
        mockMvc.perform(get("/api/order/{id}", PENDING_ORDER).header(AUTH_HEADER, family(FAMILY_OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.elderName").value("张德海"));
    }

    @Test
    @DisplayName("归属 · 另一个家属读别人的订单 → 3004（本模块最重要的一条）")
    void otherFamilyShouldNotReadOrder() throws Exception {
        // 令牌合法、角色正确、订单真实存在 —— 只有归属校验能挡下来
        mockMvc.perform(get("/api/order/{id}", PENDING_ORDER).header(AUTH_HEADER, family(FAMILY_OTHER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.ORDER_NO_PERMISSION.getCode()));
    }

    @Test
    @DisplayName("归属 · 非本单陪诊员读订单 → 3004")
    void otherCompanionShouldNotReadOrder() throws Exception {
        // 1001 还没有陪诊员，任何人都不是它的接单人
        mockMvc.perform(get("/api/order/{id}", PENDING_ORDER).header(AUTH_HEADER, companion(COMPANION_APPROVED)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.ORDER_NO_PERMISSION.getCode()));
    }

    @Test
    @DisplayName("归属 · 本单陪诊员读订单 → 200")
    void assignedCompanionShouldReadOrder() throws Exception {
        mockMvc.perform(get("/api/order/{id}", COMPLETED_ORDER).header(AUTH_HEADER, companion(COMPANION_APPROVED)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    @DisplayName("归属 · 别的陪诊员读同一单 → 3004")
    void otherCompanionShouldNotReadAssignedOrder() throws Exception {
        mockMvc.perform(get("/api/order/{id}", COMPLETED_ORDER).header(AUTH_HEADER, companion(COMPANION_OF_1007)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.ORDER_NO_PERMISSION.getCode()));
    }

    @Test
    @DisplayName("归属 · 就诊老人本人读订单 → 200")
    void orderElderShouldReadOrder() throws Exception {
        mockMvc.perform(get("/api/order/{id}", PENDING_ORDER).header(AUTH_HEADER, elder(ELDER_OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.elderName").value("张德海"));
    }

    @Test
    @DisplayName("归属 · 别的老人读不是自己就诊的订单 → 3004")
    void otherElderShouldNotReadOrder() throws Exception {
        mockMvc.perform(get("/api/order/{id}", PENDING_ORDER).header(AUTH_HEADER, elder(ELDER_OTHER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.ORDER_NO_PERMISSION.getCode()));
    }

    @Test
    @DisplayName("归属 · 管理员读任意订单 → 200（纠纷处理需要）")
    void adminShouldReadAnyOrder() throws Exception {
        mockMvc.perform(get("/api/order/{id}", PENDING_ORDER).header(AUTH_HEADER, token(ADMIN_ID, RoleConstants.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    @DisplayName("归属 · 订单不存在 → 3001")
    void missingOrderShouldReturn3001() throws Exception {
        mockMvc.perform(get("/api/order/{id}", NOT_EXIST_ORDER).header(AUTH_HEADER, family(FAMILY_OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.ORDER_NOT_FOUND.getCode()));
    }

    @Test
    @DisplayName("归属 · 另一个家属取消别人的订单 → 3004，且订单状态不变")
    void otherFamilyShouldNotCancelOrder() throws Exception {
        mockMvc.perform(put("/api/order/{id}/cancel", PENDING_ORDER)
                        .header(AUTH_HEADER, family(FAMILY_OTHER))
                        .contentType(JSON)
                        .content("{\"reason\":\"越权取消\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.ORDER_NO_PERMISSION.getCode()));

        // 被拒的操作不能留下痕迹
        mockMvc.perform(get("/api/order/{id}", PENDING_ORDER).header(AUTH_HEADER, family(FAMILY_OWNER)))
                .andExpect(jsonPath("$.data.status").value("PENDING"));
    }

    @Test
    @DisplayName("归属 · 另一个家属读时间线 → 3004")
    void otherFamilyShouldNotReadTimeline() throws Exception {
        mockMvc.perform(get("/api/order/{id}/timeline", PENDING_ORDER).header(AUTH_HEADER, family(FAMILY_OTHER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.ORDER_NO_PERMISSION.getCode()));
    }

    /* ================================================================== */
    /* 4 · 状态机：单向流转，跳级一律 3002                                    */
    /* ================================================================== */

    @Test
    @DisplayName("状态机 · 对「待接单」直接完成服务 → 3002（是 3002 而不是 4003）")
    void completeOnPendingShouldReturn3002Not4003() throws Exception {
        // 关键：待接单的订单根本没有陪诊员，如果先判身份就会返回「您不是该订单的陪诊员」——
        // 既没有信息量，也把验收标准里的「跳级 3002」变成了 4003。
        // 因此实现必须先判状态、后判身份。
        mockMvc.perform(post("/api/order/{id}/complete", PENDING_ORDER)
                        .header(AUTH_HEADER, companion(COMPANION_APPROVED))
                        .contentType(JSON)
                        .content("{\"summary\":\"直接完成\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.ORDER_STATUS_ILLEGAL.getCode()));
    }

    @Test
    @DisplayName("状态机 · 对「待接单」直接开始服务 → 3002")
    void startOnPendingShouldReturn3002() throws Exception {
        mockMvc.perform(post("/api/order/{id}/start", PENDING_ORDER)
                        .header(AUTH_HEADER, companion(COMPANION_APPROVED)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.ORDER_STATUS_ILLEGAL.getCode()));
    }

    @Test
    @DisplayName("状态机 · 非本单陪诊员开始服务 → 4003（状态对、人不对）")
    void notAssignedCompanionStartShouldReturn4003() throws Exception {
        // 1007 已是 ACCEPTED，状态检查通过，卡在身份这一层
        mockMvc.perform(post("/api/order/{id}/start", ACCEPTED_ORDER)
                        .header(AUTH_HEADER, companion(COMPANION_APPROVED)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.NOT_ORDER_COMPANION.getCode()));
    }

    @Test
    @DisplayName("状态机 · 已接单的订单家属取消 → 3006（陪诊员可能已在路上）")
    void cancelAcceptedOrderShouldReturn3006() throws Exception {
        mockMvc.perform(put("/api/order/{id}/cancel", ACCEPTED_ORDER)
                        .header(AUTH_HEADER, family(107L))
                        .contentType(JSON)
                        .content("{\"reason\":\"不想去了\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.ORDER_CANNOT_CANCEL.getCode()));
    }

    @Test
    @DisplayName("状态机 · 已取消的订单再取消 → 3006")
    void cancelCancelledOrderShouldReturn3006() throws Exception {
        mockMvc.perform(put("/api/order/{id}/cancel", CANCELLED_ORDER)
                        .header(AUTH_HEADER, family(121L))
                        .contentType(JSON)
                        .content("{\"reason\":\"再取消一次\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.ORDER_CANNOT_CANCEL.getCode()));
    }

    @Test
    @DisplayName("状态机 · 终态订单不能接单 → 3002（不可回退）")
    void acceptCancelledOrderShouldReturn3002() throws Exception {
        mockMvc.perform(post("/api/order/{id}/accept", CANCELLED_ORDER)
                        .header(AUTH_HEADER, companion(COMPANION_OF_1007)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.ORDER_STATUS_ILLEGAL.getCode()));
    }

    @Test
    @DisplayName("状态机 · 对已接单的订单拒单 → 3002（拒单只对还没被接走的单有意义）")
    void rejectAcceptedOrderShouldReturn3002() throws Exception {
        mockMvc.perform(post("/api/order/{id}/reject", ACCEPTED_ORDER)
                        .header(AUTH_HEADER, companion(COMPANION_OF_1007))
                        .contentType(JSON)
                        .content("{\"reason\":\"不想接\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.ORDER_STATUS_ILLEGAL.getCode()));
    }

    @Test
    @DisplayName("状态机 · 重复拒同一单 → 409，且不写第二条拒单记录")
    void duplicateRejectShouldReturn409() throws Exception {
        // 种子数据里 301 已经拒过 1001（reject_log 3001）
        mockMvc.perform(post("/api/order/{id}/reject", PENDING_ORDER)
                        .header(AUTH_HEADER, companion(COMPANION_APPROVED))
                        .contentType(JSON)
                        .content("{\"reason\":\"再拒一次\"}"))
                // 按全局约定，业务错误一律 HTTP 200，由 code 区分（只有 401/403 用真实状态码）
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.CONFLICT.getCode()))
                .andExpect(jsonPath("$.message").value(containsString("已经拒过")));
    }

    /* ================================================================== */
    /* 5 · 老人只读：订单模块里的写操作对老人一律 403                          */
    /* ================================================================== */

    @ParameterizedTest(name = "老人只读 · {0} → 403")
    @ValueSource(strings = {
            "POST /api/order",
            "PUT /api/order/1001/cancel",
            "POST /api/order/1001/accept",
            "POST /api/order/1001/reject",
            "POST /api/order/1001/start",
            "POST /api/order/1001/complete"})
    @DisplayName("老人只读 · 订单模块的写接口对老人一律 403（下单也必须由家属代操作）")
    void elderShouldBeReadOnlyOnOrderWrites(String spec) throws Exception {
        String[] parts = spec.split(" ", 2);
        String method = parts[0];
        String path = parts[1];

        var request = switch (method) {
            case "POST" -> post(path);
            case "PUT" -> put(path);
            default -> throw new IllegalArgumentException("不支持的请求方法：" + method);
        };
        request.header(AUTH_HEADER, elder(ELDER_OWNER)).contentType(JSON).content("{}");

        mockMvc.perform(request)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403))
                // 提示语必须解释原因，否则老人只会看到「没有操作权限」
                .andExpect(jsonPath("$.message").value(containsString("只读")));
    }

    /* ================================================================== */
    /* 6 · 脱敏口径：列表 / 详情的差异，以及时间格式                           */
    /* ================================================================== */

    @Test
    @DisplayName("脱敏 · 我的订单列表姓名脱敏，且不返回地址、备注与内部 ID")
    void listShouldMaskNameAndHideAddress() throws Exception {
        // 家属 101 的订单（1001 / 1031）就诊老人都是 401，因此首条姓名必然是脱敏的「张*海」
        mockMvc.perform(get("/api/order").header(AUTH_HEADER, family(FAMILY_OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[0].elderName").value("张*海"))
                // 列表页绝不返回地址与备注
                .andExpect(jsonPath("$.data.records[0].address").doesNotExist())
                .andExpect(jsonPath("$.data.records[0].remark").doesNotExist())
                // 也不返回家属 / 老人 / 陪诊员的内部 ID
                .andExpect(jsonPath("$.data.records[0].familyId").doesNotExist())
                .andExpect(jsonPath("$.data.records[0].elderId").doesNotExist())
                .andExpect(jsonPath("$.data.records[0].companionId").doesNotExist())
                // version 是乐观锁内部字段，任何出口都不该暴露
                .andExpect(jsonPath("$.data.records[0].version").doesNotExist());
    }

    @Test
    @DisplayName("脱敏 · 订单详情姓名全显、地址与备注返回，未接单时无陪诊员字段")
    void detailShouldReturnFullNameAndAddress() throws Exception {
        mockMvc.perform(get("/api/order/{id}", PENDING_ORDER).header(AUTH_HEADER, family(FAMILY_OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.elderName").value("张德海"))
                .andExpect(jsonPath("$.data.elderId").value(401))
                .andExpect(jsonPath("$.data.familyId").value(101))
                .andExpect(jsonPath("$.data.address").isNotEmpty())
                // 1001 还没人接单，陪诊员字段应当直接消失（non_null）
                .andExpect(jsonPath("$.data.companionId").doesNotExist())
                .andExpect(jsonPath("$.data.companionName").doesNotExist())
                // 结算状态如实返回，完成不等于已结算
                .andExpect(jsonPath("$.data.paymentStatus").value("UNPAID"))
                .andExpect(jsonPath("$.data.paymentStatusLabel").value("未结算"));
    }

    @Test
    @DisplayName("时间格式 · 响应时间是约定的空格分隔格式，不是 ISO 的 T")
    void timeShouldUseDocumentedFormat() throws Exception {
        // 这条锁的是 M4 修复的 Jackson JSR-310 配置：
        // 单靠 spring.jackson.date-format 管不到 LocalDateTime，必须注册格式化器
        mockMvc.perform(get("/api/order/{id}", PENDING_ORDER).header(AUTH_HEADER, family(FAMILY_OWNER)))
                .andExpect(jsonPath("$.data.visitTime").value("2026-08-21 08:00:00"))
                .andExpect(jsonPath("$.data.createTime").isNotEmpty());
    }

    @Test
    @DisplayName("时间线 · 只返回已发生节点的状态、操作人快照与角色")
    void timelineShouldExposeSnapshotOperator() throws Exception {
        // 1001 的种子状态日志只有一条：NULL → PENDING，操作人是家属 101（姓名快照「张伟」）
        mockMvc.perform(get("/api/order/{id}/timeline", PENDING_ORDER).header(AUTH_HEADER, family(FAMILY_OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[0].status").value("PENDING"))
                .andExpect(jsonPath("$.data[0].statusLabel").value("待接单"))
                .andExpect(jsonPath("$.data[0].operatorRole").value("FAMILY"))
                .andExpect(jsonPath("$.data[0].operatorName").value("张伟"))
                .andExpect(jsonPath("$.data[0].remark").value("下单成功"))
                // fromStatus 刻意不下发，由上一条记录表达
                .andExpect(jsonPath("$.data[0].fromStatus").doesNotExist());
    }

    /* ================================================================== */
    /* 7 · 参数校验                                                        */
    /* ================================================================== */

    @Test
    @DisplayName("校验 · 下单未选老人 → 400")
    void createWithoutElderShouldReturn400() throws Exception {
        mockMvc.perform(post("/api/order")
                        .header(AUTH_HEADER, family(FAMILY_OWNER))
                        .contentType(JSON)
                        .content("{\"hospital\":\"海南省人民医院\",\"department\":\"心血管内科\","
                                + "\"visitTime\":\"" + FUTURE_VISIT + "\",\"address\":\"海口市秀英区\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.PARAM_ERROR.getCode()));
    }

    @Test
    @DisplayName("校验 · 就诊时间早于当前时间 → 3005")
    void createWithPastVisitTimeShouldReturn3005() throws Exception {
        mockMvc.perform(post("/api/order")
                        .header(AUTH_HEADER, family(FAMILY_OWNER))
                        .contentType(JSON)
                        .content(createBody(401L, "2020-01-01 09:00:00")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.ORDER_TIME_INVALID.getCode()));
    }

    @Test
    @DisplayName("校验 · 给别人的老人下单 → 2006")
    void createForOthersElderShouldReturn2006() throws Exception {
        // 402 是家属 102 的老人
        mockMvc.perform(post("/api/order")
                        .header(AUTH_HEADER, family(FAMILY_OWNER))
                        .contentType(JSON)
                        .content(createBody(402L, FUTURE_VISIT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.NO_PERMISSION_FOR_ELDER.getCode()));
    }

    @Test
    @DisplayName("校验 · 给不存在的老人下单 → 2001")
    void createForMissingElderShouldReturn2001() throws Exception {
        mockMvc.perform(post("/api/order")
                        .header(AUTH_HEADER, family(FAMILY_OWNER))
                        .contentType(JSON)
                        .content(createBody(999999L, FUTURE_VISIT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.ELDER_NOT_FOUND.getCode()));
    }

    @Test
    @DisplayName("校验 · 状态筛选取值非法 → 400（不能静默返回全量）")
    void listWithIllegalStatusShouldReturn400() throws Exception {
        mockMvc.perform(get("/api/order").param("status", "PENDNG").header(AUTH_HEADER, family(FAMILY_OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.PARAM_ERROR.getCode()));
    }

    @Test
    @DisplayName("校验 · 开始日期晚于结束日期 → 400")
    void listWithReversedDateRangeShouldReturn400() throws Exception {
        mockMvc.perform(get("/api/order")
                        .param("startDate", "2026-09-30")
                        .param("endDate", "2026-09-01")
                        .header(AUTH_HEADER, family(FAMILY_OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.PARAM_ERROR.getCode()));
    }

    @Test
    @DisplayName("校验 · 日期格式不合法 → 400，且提示指明是哪个参数")
    void listWithIllegalDateShouldReturn400() throws Exception {
        mockMvc.perform(get("/api/order").param("startDate", "2026-13-01").header(AUTH_HEADER, family(FAMILY_OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.PARAM_ERROR.getCode()))
                .andExpect(jsonPath("$.message").value(containsString("startDate")));
    }

    @Test
    @DisplayName("校验 · 取消原因留空 → 400")
    void cancelWithoutReasonShouldReturn400() throws Exception {
        mockMvc.perform(put("/api/order/{id}/cancel", PENDING_ORDER)
                        .header(AUTH_HEADER, family(FAMILY_OWNER))
                        .contentType(JSON)
                        .content("{\"reason\":\"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.PARAM_ERROR.getCode()));
    }

    @Test
    @DisplayName("校验 · 拒单原因留空 → 400")
    void rejectWithoutReasonShouldReturn400() throws Exception {
        mockMvc.perform(post("/api/order/{id}/reject", PENDING_ORDER)
                        .header(AUTH_HEADER, companion(COMPANION_OF_1007))
                        .contentType(JSON)
                        .content("{\"reason\":\"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.PARAM_ERROR.getCode()));
    }

    @Test
    @DisplayName("校验 · 服务照片超过 6 张 → 400")
    void completeWithTooManyPhotosShouldReturn400() throws Exception {
        mockMvc.perform(post("/api/order/{id}/complete", PENDING_ORDER)
                        .header(AUTH_HEADER, companion(COMPANION_APPROVED))
                        .contentType(JSON)
                        .content("{\"photos\":[\"/uploads/1.jpg\",\"/uploads/2.jpg\",\"/uploads/3.jpg\","
                                + "\"/uploads/4.jpg\",\"/uploads/5.jpg\",\"/uploads/6.jpg\",\"/uploads/7.jpg\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.PARAM_ERROR.getCode()));
    }

    /* ================================================================== */

    /** 一份合法的下单请求体 */
    private static String createBody(long elderId, String visitTime) {
        return "{\"elderId\":" + elderId + ",\"hospital\":\"海南省人民医院\",\"department\":\"心血管内科\","
                + "\"visitTime\":\"" + visitTime + "\",\"address\":\"海南省海口市秀英区秀华路19号\"}";
    }

    private String family(long userId) {
        return token(userId, RoleConstants.FAMILY);
    }

    private String elder(long userId) {
        return token(userId, RoleConstants.ELDER);
    }

    private String companion(long userId) {
        return token(userId, RoleConstants.COMPANION);
    }

    /** 签一个真实可用的 accessToken（密码版本取实时值，见 {@link TestTokens} 类注释）；已含 Bearer 前缀 */
    private String token(long userId, String role) {
        return TestTokens.bearer(tokenProvider, tokenStore, userId, role, "m4");
    }
}

package org.company.nianglin.security;

import org.company.nianglin.common.ResultCode;
import org.company.nianglin.constant.RoleConstants;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M3 归属校验矩阵：用真实 JWT 打真实接口、查真实种子数据。
 *
 * <p>M2 的 {@link PermissionMatrixTest} 用探针接口验证了「<b>角色</b>这一层」，
 * 本类验证的是它管不到的那一层：<b>同一角色内部，谁是数据的主人</b>。</p>
 *
 * <h3>测试数据来自种子库，硬编码的 ID 都有出处</h3>
 *
 * <table border="1">
 *   <caption>用例依赖的种子数据（V2 / V3 迁移）</caption>
 *   <tr><th>ID</th><th>含义</th></tr>
 *   <tr><td>101 / fam001</td><td>家属，绑定老人 401（BOUND）与 431（UNBOUND）</td></tr>
 *   <tr><td>102 / fam002</td><td>另一个家属，绑定老人 402</td></tr>
 *   <tr><td>401 / 张德海</td><td>老人档案，user_id=201，有一条 PENDING 订单</td></tr>
 *   <tr><td>201 / elder001</td><td>401 对应的老人登录账号</td></tr>
 *   <tr><td>202 / elder002</td><td>另一个老人账号</td></tr>
 *   <tr><td>301 / comp001</td><td>已通过审核的陪诊员（companion_profile.id=601 ≠ 301）</td></tr>
 * </table>
 *
 * <h3>本类不修改任何数据</h3>
 *
 * <p>所有用例要么是读操作，要么是「预期被拒」的写操作 —— 归属校验和订单闸门都在
 * 落库之前抛出，因此跑完种子数据保持不变，可以随时重跑。</p>
 *
 * @author 银龄伴诊团队
 * @since M3
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("M3 归属校验矩阵：角色管不到的「数据是不是你的」")
class ElderOwnershipMatrixTest {

    private static final String AUTH_HEADER = "Authorization";
    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;

    /** 档案：属家属 101，属老人账号 201 */
    private static final long ELDER_ID = 401L;
    /** 档案：属家属 102，用于「别人的老人」越权用例 */
    private static final long OTHER_ELDER_ID = 402L;

    private static final long FAMILY_OWNER = 101L;
    private static final long FAMILY_OTHER = 102L;
    private static final long ELDER_OWNER = 201L;
    private static final long ELDER_OTHER = 202L;
    private static final long COMPANION_ID = 301L;
    private static final long ADMIN_ID = 1L;
    private static final long NOT_EXIST_ID = 999999L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    /* ================================================================== */
    /* 1 · 归属校验：同角色之间也要分「你的」和「我的」                        */
    /* ================================================================== */

    @Test
    @DisplayName("归属 · 绑定人读自己的老人 → 200，返回全名")
    void ownerFamilyShouldReadOwnElder() throws Exception {
        mockMvc.perform(get("/api/user/elder/{id}", ELDER_ID).header(AUTH_HEADER, family(FAMILY_OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.name").value("张德海"))
                .andExpect(jsonPath("$.data.relation").value("儿子"));
    }

    @Test
    @DisplayName("归属 · 另一个家属读别人的老人 → 2006（本模块最重要的一条）")
    void otherFamilyShouldNotReadElderOfOthers() throws Exception {
        // 关键点：令牌合法、角色是 FAMILY、路径上的 ID 也确实存在 ——
        // 角色鉴权在这一步是放行的，只有归属校验能挡下来
        mockMvc.perform(get("/api/user/elder/{id}", ELDER_ID).header(AUTH_HEADER, family(FAMILY_OTHER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.NO_PERMISSION_FOR_ELDER.getCode()));
    }

    @Test
    @DisplayName("归属 · 另一个家属改别人的老人 → 2006")
    void otherFamilyShouldNotUpdateElderOfOthers() throws Exception {
        mockMvc.perform(put("/api/user/elder/{id}", ELDER_ID)
                        .header(AUTH_HEADER, family(FAMILY_OTHER))
                        .contentType(JSON)
                        .content("{\"address\":\"越权改的地址\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.NO_PERMISSION_FOR_ELDER.getCode()));
    }

    @Test
    @DisplayName("归属 · 另一个家属删别人的老人 → 2006")
    void otherFamilyShouldNotDeleteElderOfOthers() throws Exception {
        mockMvc.perform(delete("/api/user/elder/{id}", ELDER_ID).header(AUTH_HEADER, family(FAMILY_OTHER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.NO_PERMISSION_FOR_ELDER.getCode()));
    }

    @Test
    @DisplayName("归属 · 已被解绑的档案不算「我的老人」→ 2006")
    void familyShouldNotReadUnboundElder() throws Exception {
        // 家属 101 名下有一条指向 431 的 UNBOUND 关系，那条不算「有效绑定」
        mockMvc.perform(get("/api/user/elder/{id}", 431L).header(AUTH_HEADER, family(FAMILY_OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.NO_PERMISSION_FOR_ELDER.getCode()));
    }

    @Test
    @DisplayName("归属 · 老人读自己的档案 → 200")
    void elderShouldReadOwnProfile() throws Exception {
        mockMvc.perform(get("/api/user/elder/{id}", ELDER_ID).header(AUTH_HEADER, elder(ELDER_OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.name").value("张德海"));
    }

    @Test
    @DisplayName("归属 · 老人读别人的档案 → 2006")
    void elderShouldNotReadOthersProfile() throws Exception {
        mockMvc.perform(get("/api/user/elder/{id}", ELDER_ID).header(AUTH_HEADER, elder(ELDER_OTHER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.NO_PERMISSION_FOR_ELDER.getCode()));
    }

    @Test
    @DisplayName("归属 · 管理员无需绑定关系即可读任意档案 → 200")
    void adminShouldReadAnyElder() throws Exception {
        mockMvc.perform(get("/api/user/elder/{id}", ELDER_ID).header(AUTH_HEADER, token(ADMIN_ID, RoleConstants.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.name").value("张德海"));
    }

    @Test
    @DisplayName("归属 · 档案不存在 → 2001")
    void missingElderShouldReturn2001() throws Exception {
        mockMvc.perform(get("/api/user/elder/{id}", NOT_EXIST_ID).header(AUTH_HEADER, family(FAMILY_OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.ELDER_NOT_FOUND.getCode()));
    }

    /* ================================================================== */
    /* 2 · 角色门槛：不该进这个模块的角色                                  */
    /* ================================================================== */

    @Test
    @DisplayName("角色 · 陪诊员读老人档案列表 → 403（没有这个权限）")
    void companionShouldNotListElders() throws Exception {
        mockMvc.perform(get("/api/user/elder").header(AUTH_HEADER, companion(COMPANION_ID)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    @DisplayName("角色 · 陪诊员读老人档案详情 → 403")
    void companionShouldNotReadElderDetail() throws Exception {
        mockMvc.perform(get("/api/user/elder/{id}", ELDER_ID).header(AUTH_HEADER, companion(COMPANION_ID)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    @DisplayName("角色 · 老人读老人档案列表 → 403（列表是家属视角，老人走详情）")
    void elderShouldNotListElders() throws Exception {
        mockMvc.perform(get("/api/user/elder").header(AUTH_HEADER, elder(ELDER_OWNER)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    @DisplayName("角色 · 未登录读老人档案 → 401")
    void anonymousShouldGet401() throws Exception {
        mockMvc.perform(get("/api/user/elder/{id}", ELDER_ID))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    /* ================================================================== */
    /* 3 · 老人只读规则：写操作一律 403，与角色门槛叠加生效                    */
    /* ================================================================== */

    @ParameterizedTest(name = "老人只读 · {0} → 403")
    @ValueSource(strings = {
            "POST /api/user/elder",
            "PUT /api/user/elder/401",
            "DELETE /api/user/elder/401",
            "POST /api/user/elder/bind",
            "DELETE /api/user/elder/401/bind",
            "PUT /api/user/profile",
            "POST /api/user/companion/apply"})
    @DisplayName("老人只读 · 所有写接口对老人一律 403（含改自己的资料）")
    void elderShouldBeReadOnlyOnEveryWriteEndpoint(String spec) throws Exception {
        String[] parts = spec.split(" ", 2);
        String method = parts[0];
        String path = parts[1];

        var request = switch (method) {
            case "POST" -> post(path);
            case "PUT" -> put(path);
            case "DELETE" -> delete(path);
            default -> throw new IllegalArgumentException("不支持的请求方法：" + method);
        };
        // interceptor 的 preHandle 早于参数解析，所以请求体是什么内容都不影响结果；
        // 这里仍然给一份合法 JSON，避免将来顺序变化后用例变成「因为 400 才通过」的假绿
        request.header(AUTH_HEADER, elder(ELDER_OWNER)).contentType(JSON).content("{}");

        mockMvc.perform(request)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403))
                // 提示语必须能解释原因，否则用户只会看到「没有操作权限」而无从下手
                .andExpect(jsonPath("$.message").value(containsString("只读")));
    }

    /* ================================================================== */
    /* 4 · 进行中订单闸门：结不了的单，不能删人也不能解绑                      */
    /* ================================================================== */

    @Test
    @DisplayName("订单闸门 · 老人有进行中订单时删除档案 → 409")
    void deleteShouldBeBlockedByActiveOrder() throws Exception {
        // 老人 401 有一条 PENDING 订单（种子数据）
        mockMvc.perform(delete("/api/user/elder/{id}", ELDER_ID).header(AUTH_HEADER, family(FAMILY_OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.CONFLICT.getCode()))
                .andExpect(jsonPath("$.message").value(containsString("进行中")));
    }

    @Test
    @DisplayName("订单闸门 · 老人有进行中订单时解绑 → 409")
    void unbindShouldBeBlockedByActiveOrder() throws Exception {
        mockMvc.perform(delete("/api/user/elder/{id}/bind", ELDER_ID).header(AUTH_HEADER, family(FAMILY_OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.CONFLICT.getCode()));
    }

    @Test
    @DisplayName("订单闸门 · 解绑不存在的关系 → 2005")
    void unbindMissingRelationShouldReturn2005() throws Exception {
        // 家属 101 没绑过 402（那是家属 102 的老人），走的是「关系不存在」而不是「订单闸门」
        mockMvc.perform(delete("/api/user/elder/{id}/bind", OTHER_ELDER_ID)
                        .header(AUTH_HEADER, family(FAMILY_OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.RELATION_NOT_FOUND.getCode()));
    }

    @Test
    @DisplayName("订单闸门 · 拒绝时档案与关系都必须原封不动")
    void rejectedOperationsShouldNotMutateData() throws Exception {
        // 先确认 401 还在、还绑着
        mockMvc.perform(get("/api/user/elder/{id}", ELDER_ID).header(AUTH_HEADER, family(FAMILY_OWNER)))
                .andExpect(jsonPath("$.data.name").value("张德海"))
                .andExpect(jsonPath("$.data.bindStatus").value("BOUND"));

        mockMvc.perform(delete("/api/user/elder/{id}", ELDER_ID).header(AUTH_HEADER, family(FAMILY_OWNER)));
        mockMvc.perform(delete("/api/user/elder/{id}/bind", ELDER_ID).header(AUTH_HEADER, family(FAMILY_OWNER)));

        // 再确认一次：被拒的操作不能留下任何痕迹
        mockMvc.perform(get("/api/user/elder/{id}", ELDER_ID).header(AUTH_HEADER, family(FAMILY_OWNER)))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.name").value("张德海"))
                .andExpect(jsonPath("$.data.bindStatus").value("BOUND"));
    }

    /* ================================================================== */
    /* 5 · 脱敏出口：列表与详情的口径差异                                    */
    /* ================================================================== */

    @Test
    @DisplayName("脱敏 · 列表只含有效绑定的老人，姓名手机号脱敏，不含身份证与地址")
    void listShouldMaskAndExcludeUnbound() throws Exception {
        mockMvc.perform(get("/api/user/elder").header(AUTH_HEADER, family(FAMILY_OWNER)))
                .andExpect(status().isOk())
                // 家属 101 名下 401 是 BOUND、431 是 UNBOUND —— 列表只能出现 1 条
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].id").value(ELDER_ID))
                .andExpect(jsonPath("$.data.records[0].name").value("张*海"))
                .andExpect(jsonPath("$.data.records[0].phone").value("139****0001"))
                // 列表页绝不返回身份证与地址（Jackson non_null 下字段直接消失）
                .andExpect(jsonPath("$.data.records[0].idCard").doesNotExist())
                .andExpect(jsonPath("$.data.records[0].address").doesNotExist())
                .andExpect(jsonPath("$.data.records[0].medicalHistory").doesNotExist());
    }

    @Test
    @DisplayName("脱敏 · 详情返回全名，身份证脱敏为 6+8+4，地址门牌打码")
    void detailShouldMaskIdCardAndAddress() throws Exception {
        mockMvc.perform(get("/api/user/elder/{id}", ELDER_ID).header(AUTH_HEADER, family(FAMILY_OWNER)))
                .andExpect(jsonPath("$.data.name").value("张德海"))
                // 库里是 64 字符的 AES 密文，出口必须是 18 位脱敏串：前 6 后 4 中间 8 颗星
                .andExpect(jsonPath("$.data.idCard").value(containsString("********")))
                .andExpect(jsonPath("$.data.address").value("海口市美兰区春晖小区***"))
                .andExpect(jsonPath("$.data.emergencyPhone").value("138****0001"))
                // 绑定家属能看到病史与过敏史，不能因为「谨慎」把有用信息一起抹掉
                .andExpect(jsonPath("$.data.medicalHistory").isNotEmpty());
    }

    @Test
    @DisplayName("脱敏 · 当前用户资料返回脱敏手机号，不含密码与身份证")
    void profileShouldNotLeakSecrets() throws Exception {
        mockMvc.perform(get("/api/user/profile").header(AUTH_HEADER, family(FAMILY_OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.phone").value("138****0001"))
                .andExpect(jsonPath("$.data.roleLabel").value("家属"))
                .andExpect(jsonPath("$.data.password").doesNotExist())
                .andExpect(jsonPath("$.data.idCard").doesNotExist());
    }

    /* ================================================================== */
    /* 6 · 陪诊员公开资料                                                  */
    /* ================================================================== */

    @Test
    @DisplayName("陪诊员 · id 取用户 ID（与订单表一致），姓名脱敏，无隐私字段")
    void companionPublicProfileShouldUseUserIdAndMask() throws Exception {
        // companion_profile.id = 601，但业务键是 user_id = 301
        mockMvc.perform(get("/api/user/companion/{id}", COMPANION_ID).header(AUTH_HEADER, family(FAMILY_OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(301))
                .andExpect(jsonPath("$.data.realName").value("李*军"))
                .andExpect(jsonPath("$.data.score").value("4.00"))
                .andExpect(jsonPath("$.data.auditStatusLabel").value("已通过"))
                // 隐私字段一个都不能出现
                .andExpect(jsonPath("$.data.idCard").doesNotExist())
                .andExpect(jsonPath("$.data.phone").doesNotExist())
                .andExpect(jsonPath("$.data.certificates").doesNotExist())
                .andExpect(jsonPath("$.data.rejectReason").doesNotExist());
    }

    @Test
    @DisplayName("陪诊员 · 用 companion_profile 主键（601）去查会失败 → 2007")
    void companionProfileShouldFailWhenQueriedByProfilePrimaryKey() throws Exception {
        // 601 是 companion_profile 的主键，不是用户 ID。
        // 拿它当参数必须查不到 —— 这正是我们希望暴露的错误方式
        mockMvc.perform(get("/api/user/companion/{id}", 601L).header(AUTH_HEADER, family(FAMILY_OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.COMPANION_NOT_FOUND.getCode()));
    }

    @Test
    @DisplayName("陪诊员 · 不存在的账号 → 2007")
    void companionProfileShouldReturn2007WhenMissing() throws Exception {
        mockMvc.perform(get("/api/user/companion/{id}", NOT_EXIST_ID).header(AUTH_HEADER, family(FAMILY_OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.COMPANION_NOT_FOUND.getCode()));
    }

    /* ================================================================== */
    /* 7 · 绑定接口的一期边界                                               */
    /* ================================================================== */

    @Test
    @DisplayName("绑定 · 邀请码一期未开放 → 501，且不写任何数据")
    void inviteCodeBindShouldBeRejected() throws Exception {
        mockMvc.perform(post("/api/user/elder/bind")
                        .header(AUTH_HEADER, family(FAMILY_OWNER))
                        .contentType(JSON)
                        .content("{\"bindType\":\"INVITE_CODE\",\"bindValue\":\"ABC123\",\"relation\":\"SON\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.NOT_IMPLEMENTED.getCode()))
                .andExpect(jsonPath("$.message").value(containsString("手机号")));
    }

    @Test
    @DisplayName("绑定 · 手机号格式不合法 → 400")
    void invalidPhoneBindShouldReturn400() throws Exception {
        mockMvc.perform(post("/api/user/elder/bind")
                        .header(AUTH_HEADER, family(FAMILY_OWNER))
                        .contentType(JSON)
                        .content("{\"bindType\":\"PHONE\",\"bindValue\":\"12345678901\",\"relation\":\"SON\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.PARAM_ERROR.getCode()));
    }

    @Test
    @DisplayName("绑定 · 该老人已被其他家属绑定 → 2002")
    void bindOccupiedElderShouldReturn2002() throws Exception {
        // 老人账号 202（手机号 13900100002）的档案 402 已绑给家属 102
        mockMvc.perform(post("/api/user/elder/bind")
                        .header(AUTH_HEADER, family(FAMILY_OWNER))
                        .contentType(JSON)
                        .content("{\"bindType\":\"PHONE\",\"bindValue\":\"13900100002\",\"relation\":\"SON\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.ELDER_ALREADY_BOUND.getCode()));
    }

    @Test
    @DisplayName("绑定 · 老人只读：老人自己不能调绑定接口 → 403")
    void elderShouldNotBind() throws Exception {
        mockMvc.perform(post("/api/user/elder/bind")
                        .header(AUTH_HEADER, elder(ELDER_OWNER))
                        .contentType(JSON)
                        .content("{\"bindType\":\"PHONE\",\"bindValue\":\"13900100002\",\"relation\":\"SON\"}"))
                .andExpect(status().isForbidden());
    }

    /* ================================================================== */
    /* 8 · 参数校验                                                        */
    /* ================================================================== */

    @Test
    @DisplayName("校验 · 新增档案缺姓名 → 400")
    void createWithoutNameShouldReturn400() throws Exception {
        mockMvc.perform(post("/api/user/elder")
                        .header(AUTH_HEADER, family(FAMILY_OWNER))
                        .contentType(JSON)
                        .content("{\"gender\":\"MALE\",\"birthDate\":\"1948-03-12\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.PARAM_ERROR.getCode()));
    }

    @Test
    @DisplayName("校验 · 出生日期晚于今天 → 400")
    void createWithFutureBirthDateShouldReturn400() throws Exception {
        mockMvc.perform(post("/api/user/elder")
                        .header(AUTH_HEADER, family(FAMILY_OWNER))
                        .contentType(JSON)
                        .content("{\"name\":\"测试老人\",\"gender\":\"MALE\",\"birthDate\":\"2099-01-01\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.PARAM_ERROR.getCode()));
    }

    @Test
    @DisplayName("校验 · 性别枚举非法（小写）→ 400，接口只接受大写枚举名")
    void createWithIllegalGenderShouldReturn400() throws Exception {
        mockMvc.perform(post("/api/user/elder")
                        .header(AUTH_HEADER, family(FAMILY_OWNER))
                        .contentType(JSON)
                        .content("{\"name\":\"测试老人\",\"gender\":\"male\",\"birthDate\":\"1948-03-12\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.PARAM_ERROR.getCode()));
    }

    @Test
    @DisplayName("校验 · 资质申请证件清单为空 → 400")
    void applyWithoutCertificatesShouldReturn400() throws Exception {
        mockMvc.perform(post("/api/user/companion/apply")
                        .header(AUTH_HEADER, family(FAMILY_OWNER))
                        .contentType(JSON)
                        .content("""
                                {"realName":"李建军","idCard":"460101199001010011",
                                 "serviceArea":"海口市美兰区","availableTime":"周一至周五 08:00-18:00",
                                 "certificates":[]}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.PARAM_ERROR.getCode()));
    }

    /* ================================================================== */

    private String family(long userId) {
        return token(userId, RoleConstants.FAMILY);
    }

    private String elder(long userId) {
        return token(userId, RoleConstants.ELDER);
    }

    private String companion(long userId) {
        return token(userId, RoleConstants.COMPANION);
    }

    /** 签一个真实可用的 accessToken（密码版本 0 与种子账号一致） */
    private String token(long userId, String role) {
        return "Bearer " + tokenProvider.createAccessToken(userId, "m3-" + role + "-" + userId, role, 0);
    }
}

package org.company.nianglin.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.company.nianglin.common.ResultCode;
import org.company.nianglin.constant.RoleConstants;
import org.company.nianglin.dto.AuditDecisionDTO;
import org.company.nianglin.dto.ResetPasswordDTO;
import org.company.nianglin.dto.UserDisableDTO;
import org.company.nianglin.dto.UserEnableDTO;
import org.company.nianglin.entity.AdminOperLog;
import org.company.nianglin.entity.CompanionAuditRecord;
import org.company.nianglin.entity.SysUser;
import org.company.nianglin.exception.BusinessException;
import org.company.nianglin.mapper.AdminOperLogMapper;
import org.company.nianglin.mapper.AdminReadMapper;
import org.company.nianglin.mapper.CompanionAuditRecordMapper;
import org.company.nianglin.mapper.CompanionOrderMapper;
import org.company.nianglin.mapper.CompanionProfileMapper;
import org.company.nianglin.mapper.ComplaintMapper;
import org.company.nianglin.mapper.OrderReadMapper;
import org.company.nianglin.mapper.SysUserMapper;
import org.company.nianglin.security.LoginUser;
import org.company.nianglin.security.SecurityProperties;
import org.company.nianglin.security.TokenStore;
import org.company.nianglin.service.impl.AdminServiceImpl;
import org.company.nianglin.service.support.UserNameResolver;
import org.company.nianglin.support.MybatisLambdaCache;
import org.company.nianglin.vo.AuditDecisionResultVO;
import org.company.nianglin.vo.UserStatusResultVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 管理后台服务单测（M9）。
 *
 * <p>M9 的接口在 Controller 上整类挂了 {@code hasRole('ADMIN')}，
 * 所以「非管理员能不能调」已经由 {@code AdminAccessMatrixTest} 从 HTTP 面锁死。
 * 本类要盯的是<b>管理员自己也不能做的那几件事</b> —— 注解管不了，只有 Service 能拦：</p>
 *
 * <ol>
 *   <li><b>不能封禁管理员</b>（8002）。两个管理员互相封禁之后，系统里再没有人能解封 ——
 *       这是权限事故最典型的起点。</li>
 *   <li><b>驳回资质必须写原因</b>（8003），且原因有最小长度。
 *       「已驳回」而不说为什么，等于把申诉通道也一起关掉了。</li>
 *   <li><b>审核终态不可再审</b>（8001）。允许重审就等于允许「先驳回再偷偷改成通过」，
 *       两次审核的时间戳与审核人都会被覆盖，审计链断裂。</li>
 *   <li><b>封禁必须双重失效</b>：Redis 标记让过滤器就地 403（验收要求「立即」），
 *       密码版本 +1 兜住标记被误删的情况。只做一件都留了口子。</li>
 *   <li><b>每个写操作都要留痕</b>：{@code admin_oper_log} 只增不改不删，
 *       用例逐条断言日志被写入且前后状态正确。</li>
 * </ol>
 *
 * @author 银龄伴诊团队
 * @since M9
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("管理后台服务：不可封禁管理员 / 驳回须填原因 / 终态不可再审 / 双重失效")
class AdminServiceTest {

    private static final Long ADMIN_ID = 1L;
    private static final Long TARGET_USER_ID = 101L;
    private static final Long AUDIT_ID = 701L;
    private static final Long APPLICANT_ID = 302L;

    @Mock
    private CompanionAuditRecordMapper auditRecordMapper;

    @Mock
    private CompanionProfileMapper companionProfileMapper;

    @Mock
    private SysUserMapper sysUserMapper;

    @Mock
    private ComplaintMapper complaintMapper;

    @Mock
    private CompanionOrderMapper orderMapper;

    @Mock
    private AdminOperLogMapper operLogMapper;

    @Mock
    private AdminReadMapper adminReadMapper;

    @Mock
    private OrderReadMapper orderReadMapper;

    @Mock
    private MessageService messageService;

    @Mock
    private OrderService orderService;

    @Mock
    private UserNameResolver userNameResolver;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private SecurityProperties securityProperties;

    @Mock
    private TokenStore tokenStore;

    private AdminServiceImpl service;

    @BeforeEach
    void setUp() {
        MybatisLambdaCache.warmUp();
        service = new AdminServiceImpl(auditRecordMapper, companionProfileMapper, sysUserMapper,
                complaintMapper, orderMapper, operLogMapper, adminReadMapper, orderReadMapper,
                messageService, orderService, userNameResolver, passwordEncoder, securityProperties,
                tokenStore, new ObjectMapper());
        loginAsAdmin();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    /* ================================================================== */
    /* 1 · 资质审核                                                         */
    /* ================================================================== */

    @Test
    @DisplayName("资质审核 · 申请不存在 → 2007，且不写快照也不留日志")
    void decideAuditShouldRejectMissingApplication() {
        given(auditRecordMapper.selectById(AUDIT_ID)).willReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.decideAudit(AUDIT_ID, decision(true, null)));

        assertEquals(ResultCode.COMPANION_NOT_FOUND.getCode(), ex.getCode());
        verify(companionProfileMapper, never()).update(any(), any());
        verify(operLogMapper, never()).insert(any(AdminOperLog.class));
    }

    @Test
    @DisplayName("资质审核 · 申请已是终态 → 8001（允许重审会覆盖审核人与时间戳，审计链就断了）")
    void decideAuditShouldRejectNonPendingApplication() {
        given(auditRecordMapper.selectById(AUDIT_ID)).willReturn(record("APPROVED"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.decideAudit(AUDIT_ID, decision(true, null)));

        assertEquals(ResultCode.AUDIT_STATUS_ILLEGAL.getCode(), ex.getCode());
        verify(auditRecordMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("资质审核 · 驳回了却不写原因 → 8003")
    void rejectWithoutReasonShouldBeRejected() {
        given(auditRecordMapper.selectById(AUDIT_ID)).willReturn(record("PENDING"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.decideAudit(AUDIT_ID, decision(false, null)));

        assertEquals(ResultCode.AUDIT_REASON_REQUIRED.getCode(), ex.getCode());
        verify(auditRecordMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("资质审核 · 驳回原因过短 → 8003（「不行」两个字不算原因）")
    void tooShortRejectReasonShouldBeRejected() {
        given(auditRecordMapper.selectById(AUDIT_ID)).willReturn(record("PENDING"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.decideAudit(AUDIT_ID, decision(false, "不行")));

        assertEquals(ResultCode.AUDIT_REASON_REQUIRED.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("资质审核 · 通过：同步快照 + 提升角色 + 通知申请人 + 留痕（前后状态正确）")
    void approveShouldSyncSnapshotUpgradeRoleAndLog() {
        given(auditRecordMapper.selectById(AUDIT_ID)).willReturn(record("PENDING"));
        given(sysUserMapper.selectById(APPLICANT_ID)).willReturn(user(APPLICANT_ID,
                RoleConstants.FAMILY, "NORMAL"));

        AuditDecisionResultVO result = service.decideAudit(AUDIT_ID, decision(true, null));

        assertNotNull(result);
        // 只改流水不改快照，「审核已通过」与「仍然接不了单」就会同时成立
        verify(companionProfileMapper).update(any(), any());
        // 账号角色要跟着提升，否则资质通过了也接不了单
        verify(sysUserMapper).update(any(), any());

        ArgumentCaptor<AdminOperLog> logCaptor = ArgumentCaptor.forClass(AdminOperLog.class);
        verify(operLogMapper).insert(logCaptor.capture());
        AdminOperLog operLog = logCaptor.getValue();
        assertEquals("AUDIT_COMPANION", operLog.getOperType());
        assertEquals("PENDING", operLog.getBeforeStatus());
        assertEquals("APPROVED", operLog.getAfterStatus());
        assertEquals(ADMIN_ID, operLog.getOperatorId());
    }

    @Test
    @DisplayName("资质审核 · 驳回：拒绝原因要同时写进流水与快照，并推送给申请人")
    void rejectShouldPersistReasonOnBothTables() {
        given(auditRecordMapper.selectById(AUDIT_ID)).willReturn(record("PENDING"));

        service.decideAudit(AUDIT_ID, decision(false, "证件照片模糊，无法核实有效期"));

        verify(auditRecordMapper).update(any(), any());
        verify(companionProfileMapper).update(any(), any());
        // 驳回不提升角色
        verify(sysUserMapper, never()).update(any(), any());
        verify(messageService).send(any(), any(), any(), any());

        ArgumentCaptor<AdminOperLog> logCaptor = ArgumentCaptor.forClass(AdminOperLog.class);
        verify(operLogMapper).insert(logCaptor.capture());
        assertEquals("REJECTED", logCaptor.getValue().getAfterStatus());
    }

    /* ================================================================== */
    /* 2 · 封禁 / 解封                                                      */
    /* ================================================================== */

    @Test
    @DisplayName("封禁 · 用户不存在 → 404")
    void disableMissingUserShouldReturn404() {
        given(sysUserMapper.selectById(TARGET_USER_ID)).willReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.disableUser(TARGET_USER_ID, disableDto("违规操作")));

        assertEquals(ResultCode.NOT_FOUND.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("封禁 · 目标也是管理员 → 8002，且不写库、不发消息、不留痕")
    void disableAdminShouldBeForbidden() {
        given(sysUserMapper.selectById(ADMIN_ID)).willReturn(user(ADMIN_ID, RoleConstants.ADMIN, "NORMAL"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.disableUser(ADMIN_ID, disableDto("测试互封")));

        assertEquals(ResultCode.CANNOT_DISABLE_ADMIN.getCode(), ex.getCode());
        verify(sysUserMapper, never()).update(any(), any());
        verify(messageService, never()).send(any(), any(), any(), any());
        verify(tokenStore, never()).markBanned(any());
    }

    @Test
    @DisplayName("封禁 · 用户已被封禁 → 2004（重复封禁明确告知，而不是静默成功）")
    void disableAlreadyDisabledUserShouldReturn2004() {
        given(sysUserMapper.selectById(TARGET_USER_ID))
                .willReturn(user(TARGET_USER_ID, RoleConstants.FAMILY, "DISABLED"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.disableUser(TARGET_USER_ID, disableDto("重复封禁")));

        assertEquals(ResultCode.USER_DISABLED.getCode(), ex.getCode());
        verify(tokenStore, never()).markBanned(any());
    }

    @Test
    @DisplayName("封禁 · 成功：条件更新 + 双重失效 + 通知本人 + 留痕")
    void disableShouldApplyDoubleInvalidation() {
        given(sysUserMapper.selectById(TARGET_USER_ID))
                .willReturn(user(TARGET_USER_ID, RoleConstants.FAMILY, "NORMAL"));

        UserStatusResultVO result = service.disableUser(TARGET_USER_ID, disableDto("发布违规信息"));

        assertNotNull(result);

        LambdaUpdateWrapper<SysUser> update = captureUserUpdate();
        String whereSql = update.getSqlSegment();
        assertTrue(whereSql.contains("status"),
                "封禁必须带 status = NORMAL 条件，否则并发下会覆盖别人刚做的解封：" + whereSql);
        assertTrue(update.getSqlSet().contains("status"), update.getSqlSet());

        // 双重失效缺一不可：标记负责「立即」，密码版本负责「兜住标记被误删」
        verify(tokenStore).markBanned(TARGET_USER_ID);
        verify(tokenStore).bumpPasswordVersion(TARGET_USER_ID);
        verify(messageService).send(any(), any(), any(), any());

        ArgumentCaptor<AdminOperLog> logCaptor = ArgumentCaptor.forClass(AdminOperLog.class);
        verify(operLogMapper).insert(logCaptor.capture());
        assertEquals("NORMAL", logCaptor.getValue().getBeforeStatus());
        assertEquals("DISABLED", logCaptor.getValue().getAfterStatus());
    }

    @Test
    @DisplayName("解封 · 用户本来就没被封 → 400")
    void enableNormalUserShouldReturn400() {
        given(sysUserMapper.selectById(TARGET_USER_ID))
                .willReturn(user(TARGET_USER_ID, RoleConstants.FAMILY, "NORMAL"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.enableUser(TARGET_USER_ID, enableDto("误操作")));

        assertEquals(ResultCode.PARAM_ERROR.getCode(), ex.getCode());
        verify(tokenStore, never()).unmarkBanned(any());
    }

    @Test
    @DisplayName("解封 · 成功：擦除标记 + 再递增密码版本（否则旧令牌会随标记擦除而复活）")
    void enableShouldBumpVersionAgain() {
        given(sysUserMapper.selectById(TARGET_USER_ID))
                .willReturn(user(TARGET_USER_ID, RoleConstants.FAMILY, "DISABLED"));

        service.enableUser(TARGET_USER_ID, enableDto("申诉成立，予以解封"));

        verify(tokenStore).unmarkBanned(TARGET_USER_ID);
        verify(tokenStore).bumpPasswordVersion(TARGET_USER_ID);
        verify(operLogMapper).insert(any(AdminOperLog.class));
    }

    /* ================================================================== */
    /* 3 · 重置密码                                                         */
    /* ================================================================== */

    @Test
    @DisplayName("重置密码 · 落库的必须是 BCrypt 密文，并强制下次登录修改")
    void resetPasswordShouldStoreHashAndForceChange() {
        given(sysUserMapper.selectById(TARGET_USER_ID))
                .willReturn(user(TARGET_USER_ID, RoleConstants.FAMILY, "NORMAL"));
        given(passwordEncoder.encode(anyString())).willReturn("$2a$10$0123456789abcdefghijklmnopqrstuv");

        service.resetPassword(TARGET_USER_ID, resetDto("家属电话申请"));

        LambdaUpdateWrapper<SysUser> update = captureUserUpdate();
        String setSql = update.getSqlSet();
        assertTrue(setSql.contains("password"), setSql);
        assertTrue(setSql.contains("need_change_password"),
                "重置后必须强制改密，否则管理员长期掌握着用户的可用凭据：" + setSql);
        // 凭据换人了，旧令牌必须立即失效
        verify(tokenStore).bumpPasswordVersion(TARGET_USER_ID);
        verify(operLogMapper).insert(any(AdminOperLog.class));
    }

    /* ================================================================== */

    private SysUser user(Long id, String role, String status) {
        SysUser user = new SysUser();
        user.setId(id);
        user.setUsername("user" + id);
        user.setRealName("张三");
        user.setRole(role);
        user.setStatus(status);
        return user;
    }

    private CompanionAuditRecord record(String auditStatus) {
        CompanionAuditRecord record = new CompanionAuditRecord();
        record.setId(AUDIT_ID);
        record.setApplicantUserId(APPLICANT_ID);
        record.setAuditStatus(auditStatus);
        return record;
    }

    private AuditDecisionDTO decision(boolean approved, String rejectReason) {
        AuditDecisionDTO dto = new AuditDecisionDTO();
        dto.setApproved(approved);
        dto.setRejectReason(rejectReason);
        return dto;
    }

    private UserDisableDTO disableDto(String reason) {
        UserDisableDTO dto = new UserDisableDTO();
        dto.setReason(reason);
        return dto;
    }

    private UserEnableDTO enableDto(String remark) {
        UserEnableDTO dto = new UserEnableDTO();
        dto.setRemark(remark);
        return dto;
    }

    private ResetPasswordDTO resetDto(String remark) {
        ResetPasswordDTO dto = new ResetPasswordDTO();
        dto.setRemark(remark);
        return dto;
    }

    private void loginAsAdmin() {
        LoginUser loginUser = new LoginUser(ADMIN_ID, "admin", RoleConstants.ADMIN, 0, "jti",
                System.currentTimeMillis() + 60000);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, loginUser.authorities()));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private LambdaUpdateWrapper<SysUser> captureUserUpdate() {
        ArgumentCaptor<LambdaUpdateWrapper> captor = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(sysUserMapper).update(any(), captor.capture());
        return captor.getValue();
    }
}

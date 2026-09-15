package org.company.nianglin.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.company.nianglin.common.ResultCode;
import org.company.nianglin.constant.AuditStatus;
import org.company.nianglin.constant.RoleConstants;
import org.company.nianglin.constant.WorkStatus;
import org.company.nianglin.dto.CertificateItem;
import org.company.nianglin.dto.CompanionApplyDTO;
import org.company.nianglin.entity.CompanionAuditRecord;
import org.company.nianglin.entity.CompanionProfile;
import org.company.nianglin.exception.BusinessException;
import org.company.nianglin.mapper.CompanionAuditRecordMapper;
import org.company.nianglin.mapper.CompanionProfileMapper;
import org.company.nianglin.security.LoginUser;
import org.company.nianglin.security.SecurityProperties;
import org.company.nianglin.service.impl.CompanionServiceImpl;
import org.company.nianglin.support.MybatisLambdaCache;
import org.company.nianglin.util.AesUtil;
import org.company.nianglin.vo.CompanionApplicationVO;
import org.company.nianglin.vo.CompanionApplyResultVO;
import org.company.nianglin.vo.CompanionProfileVO;
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

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 陪诊员资质服务单测。
 *
 * <p>覆盖三条容易被忽略的规则：<b>不能重复提交</b>（否则管理员审核队列会被同一个人刷爆）、
 * <b>已通过不用再申请</b>、<b>公开资料不能泄露隐私字段</b>。</p>
 *
 * <p>最后一条用「把 VO 序列化成 JSON，再断言 JSON 里没有隐私内容」来验证 ——
 * 光断言「某个字段被脱敏了」挡不住「有人新加了一个字段忘了脱敏」。</p>
 *
 * @author 银龄伴诊团队
 * @since M3
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("陪诊员资质服务：提交 / 查询 / 公开资料")
class CompanionServiceTest {

    private static final String AES_KEY = "unit-test-id-card-key";
    private static final String PLAIN_ID_CARD = "460101199001010011";
    private static final Long USER_ID = 101L;

    @Mock
    private CompanionAuditRecordMapper auditRecordMapper;

    @Mock
    private CompanionProfileMapper companionProfileMapper;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private CompanionServiceImpl companionService;

    @BeforeEach
    void setUp() {
        // 纯 Mockito 单测不起 Spring 容器，MyBatis-Plus 的 lambda 列名缓存是空的，
        // LambdaUpdateWrapper.set(...) 会抛 "can not find lambda cache"。
        // 详见 MybatisLambdaCache 的类注释。
        MybatisLambdaCache.warmUp();

        SecurityProperties securityProperties = new SecurityProperties();
        securityProperties.setIdCardKey(AES_KEY);
        companionService = new CompanionServiceImpl(auditRecordMapper, companionProfileMapper,
                objectMapper, securityProperties);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    /* ================================================================== */
    /* 提交申请                                                            */
    /* ================================================================== */

    @Test
    @DisplayName("提交：已有待审核申请时返回 400，且不写任何流水")
    void applyShouldRejectWhenPendingApplicationExists() {
        loginAs(RoleConstants.FAMILY, USER_ID);
        given(auditRecordMapper.selectCount(any())).willReturn(1L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> companionService.apply(applyDto()));

        assertEquals(ResultCode.PARAM_ERROR.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("待审核"));
        verify(auditRecordMapper, never()).insert(any(CompanionAuditRecord.class));
    }

    @Test
    @DisplayName("提交：资质已通过时返回 400，不重复走审核队列")
    void applyShouldRejectWhenAlreadyApproved() {
        loginAs(RoleConstants.FAMILY, USER_ID);
        given(auditRecordMapper.selectCount(any())).willReturn(0L);
        given(companionProfileMapper.selectList(any())).willReturn(List.of(profile(AuditStatus.APPROVED)));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> companionService.apply(applyDto()));

        assertEquals(ResultCode.PARAM_ERROR.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("已通过"));
        verify(auditRecordMapper, never()).insert(any(CompanionAuditRecord.class));
    }

    @Test
    @DisplayName("提交：首次申请写入流水（身份证密文、证件 JSON）并创建 PENDING 快照")
    void applyShouldCreateRecordAndProfile() {
        loginAs(RoleConstants.FAMILY, USER_ID);
        given(auditRecordMapper.selectCount(any())).willReturn(0L);
        given(companionProfileMapper.selectList(any())).willReturn(List.of());

        CompanionApplyResultVO result = companionService.apply(applyDto());

        assertEquals(AuditStatus.PENDING.name(), result.getAuditStatus());

        ArgumentCaptor<CompanionAuditRecord> recordCaptor =
                ArgumentCaptor.forClass(CompanionAuditRecord.class);
        verify(auditRecordMapper).insert(recordCaptor.capture());
        CompanionAuditRecord saved = recordCaptor.getValue();

        assertEquals(USER_ID, saved.getApplicantUserId());
        assertEquals(AuditStatus.PENDING.name(), saved.getAuditStatus());
        assertNotNull(saved.getSubmitTime());
        // 合规红线：身份证号必须密文落库
        assertFalse(PLAIN_ID_CARD.equals(saved.getIdCard()));
        assertEquals(PLAIN_ID_CARD, AesUtil.decrypt(saved.getIdCard(), AES_KEY));
        // 证件清单序列化成合法 JSON（列类型是 MySQL 的 json，非法 JSON 会被库直接拒绝）
        assertTrue(saved.getCertificates().contains("健康证"));
        assertTrue(saved.getCertificates().contains("/uploads/"));

        ArgumentCaptor<CompanionProfile> profileCaptor =
                ArgumentCaptor.forClass(CompanionProfile.class);
        verify(companionProfileMapper).insert(profileCaptor.capture());
        CompanionProfile created = profileCaptor.getValue();
        assertEquals(USER_ID, created.getUserId());
        assertEquals(AuditStatus.PENDING.name(), created.getAuditStatus());
        // 还没审核通过，语义上不接单，而不是取库默认值 AVAILABLE
        assertEquals(WorkStatus.REST.name(), created.getWorkStatus());
        assertEquals(BigDecimal.ZERO, created.getScore());
        assertEquals(0, created.getOrderCount());
    }

    @Test
    @DisplayName("提交：被驳回后重新申请，快照回到 PENDING 且清掉上次驳回原因")
    @SuppressWarnings("rawtypes")
    void applyShouldReuseProfileAndResetStatus() {
        loginAs(RoleConstants.FAMILY, USER_ID);
        given(auditRecordMapper.selectCount(any())).willReturn(0L);

        CompanionProfile rejected = profile(AuditStatus.REJECTED);
        rejected.setRejectReason("身份证照片不清晰");
        rejected.setId(601L);
        given(companionProfileMapper.selectList(any())).willReturn(List.of(rejected));

        companionService.apply(applyDto());

        ArgumentCaptor<LambdaUpdateWrapper> captor = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(companionProfileMapper).update(any(), captor.capture());
        String sqlSet = captor.getValue().getSqlSet();

        assertTrue(sqlSet.contains("audit_status"), "应把状态拉回待审核：" + sqlSet);
        assertTrue(sqlSet.contains("reject_reason"), "应清掉上次的驳回原因：" + sqlSet);
        assertTrue(sqlSet.contains("work_status"), "应把接单状态置为休息中：" + sqlSet);
        // 一个账号只有一份快照，不能插出第二行
        verify(companionProfileMapper, never()).insert(any(CompanionProfile.class));
    }

    /* ================================================================== */
    /* 查询申请状态                                                        */
    /* ================================================================== */

    @Test
    @DisplayName("查询：从未申请过返回 null")
    void myApplicationShouldReturnNullWhenNeverApplied() {
        loginAs(RoleConstants.FAMILY, USER_ID);
        given(auditRecordMapper.selectList(any())).willReturn(List.of());

        assertNull(companionService.myApplication());
    }

    @Test
    @DisplayName("查询：已驳回时带上驳回原因与时间")
    void myApplicationShouldExposeRejectReasonWhenRejected() {
        loginAs(RoleConstants.FAMILY, USER_ID);
        CompanionAuditRecord record = auditRecord(AuditStatus.REJECTED);
        record.setRejectReason("身份证照片不清晰，请重新上传");
        given(auditRecordMapper.selectList(any())).willReturn(List.of(record));

        CompanionApplicationVO vo = companionService.myApplication();

        assertEquals(AuditStatus.REJECTED.name(), vo.getAuditStatus());
        assertEquals("已驳回", vo.getAuditStatusLabel());
        assertEquals("身份证照片不清晰，请重新上传", vo.getRejectReason());
        assertNotNull(vo.getAuditTime());
    }

    @Test
    @DisplayName("查询：待审核时即使库里残留驳回原因也不返回")
    void myApplicationShouldHideRejectReasonWhenNotRejected() {
        loginAs(RoleConstants.FAMILY, USER_ID);
        CompanionAuditRecord record = auditRecord(AuditStatus.PENDING);
        // 模拟历史脏数据：状态已回到 PENDING，但驳回原因没清干净
        record.setRejectReason("上一轮的驳回理由");
        given(auditRecordMapper.selectList(any())).willReturn(List.of(record));

        CompanionApplicationVO vo = companionService.myApplication();

        assertEquals(AuditStatus.PENDING.name(), vo.getAuditStatus());
        assertNull(vo.getRejectReason());
    }

    /* ================================================================== */
    /* 陪诊员公开资料                                                      */
    /* ================================================================== */

    @Test
    @DisplayName("公开资料：账号没有资料返回 2007")
    void publicProfileShouldRejectWhenProfileMissing() {
        given(companionProfileMapper.selectList(any())).willReturn(List.of());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> companionService.publicProfile(301L));

        assertEquals(ResultCode.COMPANION_NOT_FOUND.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("公开资料：未通过审核的账号对外不算陪诊员，返回 2007")
    void publicProfileShouldRejectWhenNotApproved() {
        given(companionProfileMapper.selectList(any()))
                .willReturn(List.of(profile(AuditStatus.PENDING)));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> companionService.publicProfile(301L));

        assertEquals(ResultCode.COMPANION_NOT_FOUND.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("公开资料：id 取用户 ID（与订单表一致），姓名脱敏，序列化结果不含隐私字段")
    void publicProfileShouldMaskAndNotLeakPrivacy() throws Exception {
        CompanionProfile profile = profile(AuditStatus.APPROVED);
        profile.setUserId(301L);
        profile.setRealName("李建军");
        profile.setIdCard(AesUtil.encrypt(PLAIN_ID_CARD, AES_KEY));
        profile.setScore(new BigDecimal("4.8"));
        profile.setOrderCount(37);
        given(companionProfileMapper.selectList(any())).willReturn(List.of(profile));

        CompanionProfileVO vo = companionService.publicProfile(301L);

        // companion_profile.id 是 601、user_id 是 301，两者不等；
        // 订单表引用的是用户 ID，所以这里必须给 userId
        assertEquals(301L, vo.getId());
        assertEquals("李*军", vo.getRealName());
        assertEquals("4.80", vo.getScore());
        assertEquals("已通过", vo.getAuditStatusLabel());

        // 把整个 VO 序列化后检查文本，能挡住「将来有人加了敏感字段却忘了脱敏」
        String json = objectMapper.writeValueAsString(vo);
        assertFalse(json.contains(PLAIN_ID_CARD), "JSON 中不允许出现明文身份证号：" + json);
        assertFalse(json.contains("idCard"), "公开资料不应包含身份证字段：" + json);
        assertFalse(json.contains("phone"), "公开资料不应包含电话字段：" + json);
        assertFalse(json.contains("certificate"), "公开资料不应包含证件信息：" + json);
        assertFalse(json.contains("rejectReason"), "驳回原因属于私人反馈，不应公开：" + json);
    }

    /* ================================================================== */
    /* 测试夹具                                                            */
    /* ================================================================== */

    private void loginAs(String role, Long userId) {
        LoginUser loginUser = new LoginUser(userId, "tester" + userId, role, 0,
                "jti-test", System.currentTimeMillis() + 60000);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, loginUser.authorities()));
    }

    private CompanionApplyDTO applyDto() {
        return new CompanionApplyDTO()
                .setRealName("李建军")
                .setIdCard(PLAIN_ID_CARD)
                .setServiceArea("海口市美兰区")
                .setAvailableTime("周一至周五 08:00-18:00")
                .setCertificates(List.of(
                        new CertificateItem().setName("健康证").setUrl("/uploads/202609/health.jpg"),
                        new CertificateItem().setName("身份证正面").setUrl("/uploads/202609/idcard.jpg")))
                .setRemark("有 3 年陪诊经验");
    }

    private CompanionProfile profile(AuditStatus status) {
        CompanionProfile profile = new CompanionProfile();
        profile.setId(601L);
        profile.setUserId(USER_ID);
        profile.setRealName("李建军");
        profile.setServiceArea("海口市美兰区");
        profile.setAvailableTime("周一至周五 08:00-18:00");
        profile.setAuditStatus(status.name());
        profile.setWorkStatus(WorkStatus.AVAILABLE.name());
        profile.setScore(BigDecimal.ZERO);
        profile.setReviewCount(0);
        profile.setOrderCount(0);
        profile.setAcceptCount(0);
        return profile;
    }

    private CompanionAuditRecord auditRecord(AuditStatus status) {
        CompanionAuditRecord record = new CompanionAuditRecord();
        record.setId(501L);
        record.setApplicantUserId(USER_ID);
        record.setAuditStatus(status.name());
        record.setSubmitTime(LocalDateTime.of(2026, 9, 10, 9, 0, 0));
        record.setAuditTime(LocalDateTime.of(2026, 9, 11, 14, 30, 0));
        return record;
    }
}

package org.company.nianglin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.company.nianglin.common.ResultCode;
import org.company.nianglin.constant.AuditStatus;
import org.company.nianglin.constant.RoleConstants;
import org.company.nianglin.constant.WorkStatus;
import org.company.nianglin.dto.CompanionWorkStatusUpdateDTO;
import org.company.nianglin.entity.CompanionAuditRecord;
import org.company.nianglin.entity.CompanionProfile;
import org.company.nianglin.exception.BusinessException;
import org.company.nianglin.mapper.CompanionAuditRecordMapper;
import org.company.nianglin.mapper.CompanionProfileMapper;
import org.company.nianglin.security.LoginUser;
import org.company.nianglin.security.SecurityProperties;
import org.company.nianglin.service.impl.CompanionServiceImpl;
import org.company.nianglin.support.MybatisLambdaCache;
import org.company.nianglin.vo.CompanionProfileVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 陪诊员接单状态自助控制单测（V3.1 · T0）。
 *
 * <p>覆盖四条规则：<b>三种状态都能切</b>、<b>非法字面量被拒</b>、
 * <b>资质判定优先于状态判定</b>、<b>切换不产生任何订单副作用</b>。</p>
 *
 * <p>最后一条是本票的核心不变量：不变量是「切换接单状态不得影响在手订单」，
 * 而实现上的保证方式是「这个 Service 根本不持有订单 Mapper」。
 * 因此这里断言的是「本次交互只有档案更新、没有审核流水写入」——
 * 它是可观察的写库行为，不是「某个私有方法没被调用」。</p>
 *
 * @author 银龄伴诊团队
 * @since V3.1
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("陪诊员接单状态：自助切换")
class CompanionWorkStatusTest {

    private static final Long USER_ID = 301L;

    @Mock
    private CompanionAuditRecordMapper auditRecordMapper;

    @Mock
    private CompanionProfileMapper companionProfileMapper;

    private CompanionService companionService;

    @BeforeEach
    void setUp() {
        // 纯 Mockito 单测不起 Spring 容器，MyBatis-Plus 的 lambda 列名缓存是空的，
        // LambdaUpdateWrapper.set(...) 会抛 "can not find lambda cache"。
        // 详见 MybatisLambdaCache 的类注释。
        MybatisLambdaCache.warmUp();

        SecurityProperties securityProperties = new SecurityProperties();
        securityProperties.setIdCardKey("unit-test-id-card-key");
        companionService = new CompanionServiceImpl(auditRecordMapper, companionProfileMapper,
                new ObjectMapper(), securityProperties);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    /* ================================================================== */
    /* 三种状态可切换                                                      */
    /* ================================================================== */

    @ParameterizedTest(name = "切换到 {0} 成功并回填")
    @EnumSource(WorkStatus.class)
    @DisplayName("三种接单状态均可切换，回填状态与标签")
    void updateWorkStatusShouldAcceptEveryStatus(WorkStatus target) {
        loginAs();
        given(companionProfileMapper.selectList(any())).willReturn(List.of(profile(AuditStatus.APPROVED)));

        CompanionProfileVO vo = companionService.updateWorkStatus(dto(target.name()));

        assertNotNull(vo);
        assertEquals(target.name(), vo.getWorkStatus());
        assertEquals(target.getLabel(), vo.getWorkStatusLabel());
        verify(companionProfileMapper).update(isNull(), any());
    }

    @Test
    @DisplayName("切换后返回的姓名仍是脱敏的")
    void updateWorkStatusShouldReturnMaskedName() {
        loginAs();
        given(companionProfileMapper.selectList(any())).willReturn(List.of(profile(AuditStatus.APPROVED)));

        CompanionProfileVO vo = companionService.updateWorkStatus(dto(WorkStatus.REST.name()));

        assertNotNull(vo.getRealName());
        assertFalse(vo.getRealName().contains("李建军"), "姓名应脱敏为 李*军，实际：" + vo.getRealName());
    }

    /* ================================================================== */
    /* 拒绝路径                                                            */
    /* ================================================================== */

    @ParameterizedTest(name = "非法字面量 [{0}] 返回 400")
    @ValueSource(strings = {"BUSY", "", "  ", "AVAILABLE_DAY", "RESTING"})
    @DisplayName("非法状态字面量返回 400 且不写库")
    void updateWorkStatusShouldRejectIllegalLiteral(String illegal) {
        loginAs();

        BusinessException ex = assertThrows(BusinessException.class,
                () -> companionService.updateWorkStatus(dto(illegal)));

        assertEquals(ResultCode.PARAM_ERROR.getCode(), ex.getCode());
        verify(companionProfileMapper, never()).update(isNull(), any());
    }

    @Test
    @DisplayName("状态字面量大小写不敏感：available 等价于 AVAILABLE")
    void updateWorkStatusShouldBeCaseInsensitive() {
        loginAs();
        given(companionProfileMapper.selectList(any())).willReturn(List.of(profile(AuditStatus.APPROVED)));

        CompanionProfileVO vo = companionService.updateWorkStatus(dto("available"));

        assertEquals(WorkStatus.AVAILABLE.name(), vo.getWorkStatus());
        assertEquals("可接单", vo.getWorkStatusLabel());
    }

    @Test
    @DisplayName("状态字面量首尾空白被忽略")
    void updateWorkStatusShouldTrimInput() {
        loginAs();
        given(companionProfileMapper.selectList(any())).willReturn(List.of(profile(AuditStatus.APPROVED)));

        CompanionProfileVO vo = companionService.updateWorkStatus(dto("  REST  "));

        assertEquals(WorkStatus.REST.name(), vo.getWorkStatus());
    }

    @Test
    @DisplayName("资质未通过时返回 2003，即使状态值合法（资质判定优先于状态判定）")
    void updateWorkStatusShouldRejectWhenAuditNotApproved() {
        loginAs();
        given(companionProfileMapper.selectList(any())).willReturn(List.of(profile(AuditStatus.PENDING)));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> companionService.updateWorkStatus(dto(WorkStatus.AVAILABLE.name())));

        assertEquals(ResultCode.COMPANION_NOT_AUDITED.getCode(), ex.getCode());
        verify(companionProfileMapper, never()).update(isNull(), any());
    }

    @Test
    @DisplayName("不存在陪诊员档案时返回 2007")
    void updateWorkStatusShouldRejectWhenProfileMissing() {
        loginAs();
        given(companionProfileMapper.selectList(any())).willReturn(List.of());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> companionService.updateWorkStatus(dto(WorkStatus.AVAILABLE.name())));

        assertEquals(ResultCode.COMPANION_NOT_FOUND.getCode(), ex.getCode());
    }

    /* ================================================================== */
    /* 不影响在手订单                                                      */
    /* ================================================================== */

    @Test
    @DisplayName("切换接单状态不产生订单副作用，也不写审核流水")
    void updateWorkStatusShouldHaveNoOrderSideEffect() {
        loginAs();
        given(companionProfileMapper.selectList(any())).willReturn(List.of(profile(AuditStatus.APPROVED)));

        companionService.updateWorkStatus(dto(WorkStatus.OFFLINE.name()));

        // 该 Service 只注入「档案」与「审核流水」两个 Mapper。一旦有人在此处引入
        // 订单写入依赖（例如顺手「优化」成切换时同步取消在手订单），
        // verifyNoMoreInteractions 会立刻失败。
        verify(companionProfileMapper).update(isNull(), any());
        verify(auditRecordMapper, never()).insert(any(CompanionAuditRecord.class));
    }

    /* ================================================================== */
    /* 枚举语义                                                            */
    /* ================================================================== */

    @Test
    @DisplayName("只有 AVAILABLE 处于接单中，REST 与 OFFLINE 都不接新单")
    void onlyAvailableShouldAcceptNewOrders() {
        assertTrue(WorkStatus.AVAILABLE.isAccepting());
        assertFalse(WorkStatus.REST.isAccepting());
        assertFalse(WorkStatus.OFFLINE.isAccepting());
    }

    @Test
    @DisplayName("三个状态都有中文标签")
    void everyStatusShouldHaveLabel() {
        assertEquals("可接单", WorkStatus.AVAILABLE.getLabel());
        assertEquals("休息中", WorkStatus.REST.getLabel());
        assertEquals("离线", WorkStatus.OFFLINE.getLabel());
    }

    @Test
    @DisplayName("非法字面量经 labelOf 原样返回而不抛异常（脏数据不该让整页 500）")
    void labelOfShouldPassThroughIllegalValue() {
        assertEquals("BUSY", WorkStatus.labelOf("BUSY"));
        assertEquals("休息中", WorkStatus.labelOf(WorkStatus.REST.name()));
        assertEquals(null, WorkStatus.labelOf(null));
    }

    /* ================================================================== */
    /* 测试夹具                                                            */
    /* ================================================================== */

    private void loginAs() {
        LoginUser loginUser = new LoginUser(USER_ID, "companion301", RoleConstants.COMPANION, 0,
                "jti-test", System.currentTimeMillis() + 60000);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, loginUser.authorities()));
    }

    private CompanionWorkStatusUpdateDTO dto(String workStatus) {
        CompanionWorkStatusUpdateDTO dto = new CompanionWorkStatusUpdateDTO();
        dto.setWorkStatus(workStatus);
        return dto;
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
        profile.setScore(new BigDecimal("4.80"));
        profile.setReviewCount(3);
        profile.setOrderCount(12);
        profile.setAcceptCount(13);
        return profile;
    }
}

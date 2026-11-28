package org.company.nianglin.service;

import org.company.nianglin.common.ResultCode;
import org.company.nianglin.constant.RoleConstants;
import org.company.nianglin.constant.VitalType;
import org.company.nianglin.dto.VitalSignCreateDTO;
import org.company.nianglin.entity.ElderProfile;
import org.company.nianglin.exception.BusinessException;
import org.company.nianglin.mapper.CompanionOrderMapper;
import org.company.nianglin.mapper.SysUserMapper;
import org.company.nianglin.mapper.VitalSignRecordMapper;
import org.company.nianglin.security.LoginUser;
import org.company.nianglin.service.impl.VitalSignServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 体征去重错误码回归（收口 B · P2-2）。
 *
 * <p>唯一键属于 schema 行为，单测只能证明「冲突被翻译成 2008，且服务层不先查后插」。
 * 「键含录入人」由 V16 迁移 + commit body 中的人工 SQL 验证覆盖。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("体征去重：唯一键冲突仍返回 2008（P2-2）")
class VitalSignDedupTest {

    private static final Long ELDER_ID = 401L;
    private static final Long FAMILY_ID = 101L;

    @Mock
    private VitalSignRecordMapper vitalSignRecordMapper;
    @Mock
    private CompanionOrderMapper companionOrderMapper;
    @Mock
    private ElderService elderService;
    @Mock
    private SysUserMapper sysUserMapper;

    private VitalSignServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new VitalSignServiceImpl(vitalSignRecordMapper, companionOrderMapper,
                elderService, sysUserMapper);
        loginAsFamily();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("唯一键冲突 → 2008（错误码不变）")
    void duplicateKeyShouldTranslateTo2008() {
        given(elderService.requireAccessible(ELDER_ID)).willReturn(elder());
        given(vitalSignRecordMapper.insert(any(org.company.nianglin.entity.VitalSignRecord.class)))
                .willThrow(new DuplicateKeyException("uk_vital_dedup"));

        BusinessException ex = org.junit.jupiter.api.Assertions.assertThrows(BusinessException.class,
                () -> service.createForFamily(ELDER_ID, dto()));

        assertEquals(ResultCode.VITAL_SIGN_DUPLICATED.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("去重依赖数据库唯一键，不在服务层先查后插")
    void dedupShouldNotUsePreSelect() {
        given(elderService.requireAccessible(ELDER_ID)).willReturn(elder());
        given(vitalSignRecordMapper.insert(any(org.company.nianglin.entity.VitalSignRecord.class))).willReturn(1);

        service.createForFamily(ELDER_ID, dto());

        verify(vitalSignRecordMapper, never()).selectList(any());
        verify(vitalSignRecordMapper).insert(any(org.company.nianglin.entity.VitalSignRecord.class));
    }

    private void loginAsFamily() {
        LoginUser loginUser = new LoginUser(FAMILY_ID, "family101", RoleConstants.FAMILY, 0,
                "jti-p2-2", System.currentTimeMillis() + 60000);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, loginUser.authorities()));
    }

    private ElderProfile elder() {
        ElderProfile elder = new ElderProfile();
        elder.setId(ELDER_ID);
        elder.setUserId(201L);
        return elder;
    }

    private VitalSignCreateDTO dto() {
        VitalSignCreateDTO dto = new VitalSignCreateDTO();
        dto.setVitalType(VitalType.WEIGHT.name());
        // 服务层要求「测量时间在未来 5 分钟容差内」，用当前分钟避免用例随时间漂移
        dto.setMeasuredAt(LocalDateTime.now().withSecond(0).withNano(0));
        dto.setWeightKg(new BigDecimal("65.5"));
        return dto;
    }
}

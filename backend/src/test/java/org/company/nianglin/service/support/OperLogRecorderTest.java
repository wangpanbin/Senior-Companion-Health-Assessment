package org.company.nianglin.service.support;

import org.company.nianglin.constant.OperTargetType;
import org.company.nianglin.constant.OperType;
import org.company.nianglin.constant.RoleConstants;
import org.company.nianglin.entity.AdminOperLog;
import org.company.nianglin.mapper.AdminOperLogMapper;
import org.company.nianglin.security.LoginUser;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("管理员操作审计模块")
class OperLogRecorderTest {

    private static final Long ADMIN_ID = 1L;

    @Mock
    private AdminOperLogMapper operLogMapper;

    @Mock
    private UserNameResolver userNameResolver;

    private OperLogRecorder recorder;

    @BeforeEach
    void setUp() {
        recorder = new OperLogRecorderImpl(operLogMapper, userNameResolver);
        given(userNameResolver.resolve(ADMIN_ID)).willReturn("管理员");
        LoginUser loginUser = new LoginUser(ADMIN_ID, "admin", RoleConstants.ADMIN, 0, "jti",
                System.currentTimeMillis() + 60000);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, loginUser.authorities()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("记录当前管理员与目标状态")
    void recordShouldPersistCurrentActorAndTargetState() {
        recorder.record(OperType.REVIEW_RULING, OperTargetType.REVIEW, 30001L,
                "评价 #30001", "IS_VALID:1", "IS_VALID:0", "理由");

        ArgumentCaptor<AdminOperLog> captor = ArgumentCaptor.forClass(AdminOperLog.class);
        verify(operLogMapper).insert(captor.capture());
        AdminOperLog log = captor.getValue();
        assertEquals(ADMIN_ID, log.getOperatorId());
        assertEquals("管理员", log.getOperatorName());
        assertEquals(OperType.REVIEW_RULING.name(), log.getOperType());
        assertEquals(OperTargetType.REVIEW.name(), log.getTargetType());
        assertEquals(30001L, log.getTargetId());
        assertEquals("IS_VALID:1", log.getBeforeStatus());
        assertEquals("IS_VALID:0", log.getAfterStatus());
        assertEquals("理由", log.getRemark());
        assertNotNull(log.getOperTime());
    }
}

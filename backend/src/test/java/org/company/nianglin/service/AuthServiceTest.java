package org.company.nianglin.service;

import org.company.nianglin.common.ClientInfo;
import org.company.nianglin.common.ResultCode;
import org.company.nianglin.constant.AccountStatus;
import org.company.nianglin.constant.AuthLogConstants;
import org.company.nianglin.constant.RoleConstants;
import org.company.nianglin.dto.ChangePasswordDTO;
import org.company.nianglin.dto.LoginDTO;
import org.company.nianglin.dto.RegisterDTO;
import org.company.nianglin.entity.SysLoginLog;
import org.company.nianglin.entity.SysUser;
import org.company.nianglin.exception.BusinessException;
import org.company.nianglin.mapper.SysLoginLogMapper;
import org.company.nianglin.mapper.SysUserMapper;
import org.company.nianglin.security.JwtProperties;
import org.company.nianglin.security.JwtTokenProvider;
import org.company.nianglin.security.LoginUser;
import org.company.nianglin.security.SecurityProperties;
import org.company.nianglin.security.TokenPayload;
import org.company.nianglin.security.TokenStore;
import org.company.nianglin.service.impl.AuthServiceImpl;
import org.company.nianglin.vo.LoginVO;
import org.company.nianglin.vo.TokenVO;
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
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 认证服务单测。
 *
 * <p>重点覆盖<b>安全语义</b>而非「代码跑没跑通」：账号枚举防护、锁定策略、
 * 封禁拦截、改密后令牌失效。这些逻辑一旦回归，是线上被人打穿级别的后果。</p>
 *
 * @author 银龄伴诊团队
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("认证服务：登录 / 刷新 / 注册 / 改密")
class AuthServiceTest {

    private static final String RAW_PASSWORD = "Nl@123456";
    private static final String ENCODED_PASSWORD = "$2a$10$abcdefghijklmnopqrstuvwxyz0123456789ABCDEFGHIJKLMN";
    private static final ClientInfo CLIENT = new ClientInfo("127.0.0.1", "JUnit");

    @Mock
    private SysUserMapper sysUserMapper;

    @Mock
    private SysLoginLogMapper sysLoginLogMapper;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private CaptchaService captchaService;

    @Mock
    private JwtTokenProvider tokenProvider;

    @Mock
    private TokenStore tokenStore;

    @Mock
    private ElderService elderService;

    private AuthServiceImpl authService;

    @BeforeEach
    void setUp() {
        authService = new AuthServiceImpl(sysUserMapper, sysLoginLogMapper, passwordEncoder,
                captchaService, tokenProvider, new JwtProperties(), tokenStore, new SecurityProperties(),
                elderService);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    /* ================================================================== */
    /* 登录                                                                */
    /* ================================================================== */

    @Test
    @DisplayName("验证码错误：返回 1003，且失败原因如实落库")
    void loginShouldRejectWhenCaptchaInvalid() {
        willThrow(new BusinessException(ResultCode.CAPTCHA_ERROR))
                .given(captchaService).validate(anyString(), anyString());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> authService.login(loginDto(), CLIENT));

        assertEquals(ResultCode.CAPTCHA_ERROR.getCode(), ex.getCode());
        // 验证码错误不该去查数据库、更不该动失败计数
        verify(sysUserMapper, never()).selectOne(any());
        verify(tokenStore, never()).increaseLoginFail(anyString(), anyLong());
        assertLoginLog(AuthLogConstants.RESULT_FAIL, AuthLogConstants.REASON_BAD_CAPTCHA);
    }

    @Test
    @DisplayName("连续失败达阈值：返回 1004，且不再校验密码")
    void loginShouldRejectWhenLocked() {
        given(tokenStore.loginFailCount(anyString()))
                .willReturn((long) new SecurityProperties().getLoginFailThreshold());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> authService.login(loginDto(), CLIENT));

        assertEquals(ResultCode.LOGIN_LOCKED.getCode(), ex.getCode());
        verify(sysUserMapper, never()).selectOne(any());
        assertLoginLog(AuthLogConstants.RESULT_FAIL, AuthLogConstants.REASON_LOGIN_LOCKED);
    }

    @Test
    @DisplayName("账号不存在与密码错误：返回同一个错误码 1001（防账号枚举）")
    void loginShouldNotLeakWhetherAccountExists() {
        // ① 账号不存在
        given(sysUserMapper.selectOne(any())).willReturn(null);
        BusinessException notFound = assertThrows(BusinessException.class,
                () -> authService.login(loginDto(), CLIENT));
        assertEquals(ResultCode.LOGIN_FAILED.getCode(), notFound.getCode());

        // ② 账号存在但密码错误
        SysUser user = normalUser();
        given(sysUserMapper.selectOne(any())).willReturn(user);
        given(passwordEncoder.matches(anyString(), anyString())).willReturn(false);
        BusinessException badPassword = assertThrows(BusinessException.class,
                () -> authService.login(loginDto(), CLIENT));

        assertEquals(ResultCode.LOGIN_FAILED.getCode(), badPassword.getCode());
        assertEquals(notFound.getMessage(), badPassword.getMessage());
    }

    @Test
    @DisplayName("账号已被封禁：即使密码正确也返回 1002")
    void loginShouldRejectDisabledAccount() {
        SysUser user = normalUser();
        user.setStatus(AccountStatus.DISABLED);
        given(sysUserMapper.selectOne(any())).willReturn(user);
        given(passwordEncoder.matches(anyString(), anyString())).willReturn(true);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> authService.login(loginDto(), CLIENT));

        assertEquals(ResultCode.ACCOUNT_DISABLED.getCode(), ex.getCode());
        // 封禁不是「密码错误」，不该给失败计数加压，否则等于让攻击者帮我们锁死受害者
        verify(tokenStore, never()).increaseLoginFail(anyString(), anyLong());
    }

    @Test
    @DisplayName("登录成功：清零失败计数、写成功日志、下发双令牌")
    void loginShouldSucceedAndResetFailCounter() {
        SysUser user = normalUser();
        given(sysUserMapper.selectOne(any())).willReturn(user);
        given(passwordEncoder.matches(anyString(), anyString())).willReturn(true);
        given(tokenProvider.createAccessToken(anyLong(), anyString(), anyString(), anyInt())).willReturn("access-token");
        given(tokenProvider.createRefreshToken(anyLong(), anyString(), anyString(), anyInt())).willReturn("refresh-token");

        LoginVO vo = authService.login(loginDto(), CLIENT);

        assertEquals("access-token", vo.getAccessToken());
        assertEquals("refresh-token", vo.getRefreshToken());
        assertEquals(new JwtProperties().accessTtlSeconds(), vo.getExpiresIn());
        verify(tokenStore).clearLoginFail(anyString());
        assertLoginLog(AuthLogConstants.RESULT_SUCCESS, null);
        // 手机号必须已脱敏，这是合规红线
        assertEquals("138****8888", vo.getUserInfo().getPhone());
    }

    /* ================================================================== */
    /* 刷新令牌                                                            */
    /* ================================================================== */

    @Test
    @DisplayName("用 accessToken 冒充 refreshToken：返回 1005")
    void refreshShouldRejectAccessToken() {
        given(tokenProvider.parse(anyString())).willReturn(
                new TokenPayload(1L, "family001", RoleConstants.FAMILY,
                        JwtTokenProvider.TYPE_ACCESS, "jti-1", 0,
                        System.currentTimeMillis(), System.currentTimeMillis() + 60000));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> authService.refresh("access-token", CLIENT));

        assertEquals(ResultCode.TOKEN_INVALID.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("refreshToken 已登出（黑名单命中）：返回 1005")
    void refreshShouldRejectBlacklistedToken() {
        given(tokenProvider.parse(anyString())).willReturn(refreshPayload());
        given(tokenStore.isBlacklisted(anyString())).willReturn(true);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> authService.refresh("refresh-token", CLIENT));

        assertEquals(ResultCode.TOKEN_INVALID.getCode(), ex.getCode());
        verify(sysUserMapper, never()).selectById(anyLong());
    }

    @Test
    @DisplayName("刷新成功：只换 accessToken，refreshToken 保持不滚动")
    void refreshShouldIssueNewAccessToken() {
        given(tokenProvider.parse(anyString())).willReturn(refreshPayload());
        given(sysUserMapper.selectById(anyLong())).willReturn(normalUser());
        given(tokenProvider.createAccessToken(anyLong(), anyString(), anyString(), anyInt())).willReturn("new-access-token");

        TokenVO vo = authService.refresh("refresh-token", CLIENT);

        // TokenVO 里根本没有 refreshToken 字段，这是刻意设计：若每次刷新都续签刷新令牌，
        // 「刷新一次延长 7 天」会让令牌实际永不过期，等于没有过期策略
        assertEquals("new-access-token", vo.getAccessToken());
        assertEquals(new JwtProperties().accessTtlSeconds(), vo.getExpiresIn());
        assertLoginLog(AuthLogConstants.RESULT_SUCCESS, null);
    }

    @Test
    @DisplayName("用户已被封禁：刷新时返回 1002，不必等访问令牌自然过期")
    void refreshShouldRejectDisabledUser() {
        given(tokenProvider.parse(anyString())).willReturn(refreshPayload());
        SysUser user = normalUser();
        user.setStatus(AccountStatus.DISABLED);
        given(sysUserMapper.selectById(anyLong())).willReturn(user);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> authService.refresh("refresh-token", CLIENT));

        assertEquals(ResultCode.ACCOUNT_DISABLED.getCode(), ex.getCode());
    }

    /* ================================================================== */
    /* 注册                                                                */
    /* ================================================================== */

    @Test
    @DisplayName("注册为 ADMIN：一律拒绝（权限体系的根）")
    void registerShouldRejectAdminRole() {
        RegisterDTO dto = registerDto();
        dto.setRole(RoleConstants.ADMIN);

        BusinessException ex = assertThrows(BusinessException.class, () -> authService.register(dto));

        assertEquals(ResultCode.PARAM_ERROR.getCode(), ex.getCode());
        // MyBatis-Plus 的 BaseMapper 同时有 insert(T) 与 insert(Collection<T>)，
        // 必须显式给出参数类型，否则 any() 会产生歧义引用
        verify(sysUserMapper, never()).insert(any(SysUser.class));
    }

    @Test
    @DisplayName("手机号已注册：返回 1007")
    void registerShouldRejectDuplicatePhone() {
        // 第一次查用户名（不存在），第二次查手机号（已存在）—— 顺序与 Service 实现一致
        given(sysUserMapper.selectCount(any())).willReturn(0L, 1L);

        BusinessException ex = assertThrows(BusinessException.class, () -> authService.register(registerDto()));

        assertEquals(ResultCode.PHONE_ALREADY_EXISTS.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("注册成功：密码以 BCrypt 哈希入库，明文不出现在实体里")
    void registerShouldEncodePassword() {
        given(sysUserMapper.selectCount(any())).willReturn(0L);
        given(passwordEncoder.encode(RAW_PASSWORD)).willReturn(ENCODED_PASSWORD);

        authService.register(registerDto());

        ArgumentCaptor<SysUser> captor = ArgumentCaptor.forClass(SysUser.class);
        verify(sysUserMapper).insert(captor.capture());
        SysUser saved = captor.getValue();
        assertEquals(ENCODED_PASSWORD, saved.getPassword());
        assertTrue(saved.getPassword().startsWith("$2a$"));
        assertEquals(AccountStatus.NORMAL, saved.getStatus());
    }

    /* ================================================================== */
    /* 修改密码                                                            */
    /* ================================================================== */

    @Test
    @DisplayName("两次新密码不一致：返回 400")
    void changePasswordShouldRejectMismatchedConfirm() {
        loginAs(normalUser());
        ChangePasswordDTO dto = changePasswordDto(RAW_PASSWORD, "xyz123456");
        // 刻意让「确认密码」与「新密码」不同
        dto.setConfirmPassword("xyz654321");

        BusinessException ex = assertThrows(BusinessException.class, () -> authService.changePassword(dto));

        assertEquals(ResultCode.PARAM_ERROR.getCode(), ex.getCode());
        // 校验不通过就不该去碰数据库
        verify(sysUserMapper, never()).selectById(anyLong());
    }

    @Test
    @DisplayName("新密码与原密码相同：返回 400")
    void changePasswordShouldRejectSameAsOld() {
        loginAs(normalUser());
        ChangePasswordDTO dto = changePasswordDto(RAW_PASSWORD, RAW_PASSWORD);

        BusinessException ex = assertThrows(BusinessException.class, () -> authService.changePassword(dto));

        assertEquals(ResultCode.PARAM_ERROR.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("原密码错误：返回 1006")
    void changePasswordShouldRejectWrongOldPassword() {
        loginAs(normalUser());
        given(sysUserMapper.selectById(anyLong())).willReturn(normalUser());
        given(passwordEncoder.matches(anyString(), anyString())).willReturn(false);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> authService.changePassword(changePasswordDto(RAW_PASSWORD, "xyz123456")));

        assertEquals(ResultCode.OLD_PASSWORD_ERROR.getCode(), ex.getCode());
        verify(tokenStore, never()).bumpPasswordVersion(anyLong());
    }

    @Test
    @DisplayName("改密成功：密码版本 +1，令全部已签发令牌失效")
    void changePasswordShouldBumpPasswordVersion() {
        loginAs(normalUser());
        given(sysUserMapper.selectById(anyLong())).willReturn(normalUser());
        given(passwordEncoder.matches(anyString(), anyString())).willReturn(true);
        given(passwordEncoder.encode(anyString())).willReturn(ENCODED_PASSWORD);

        authService.changePassword(changePasswordDto(RAW_PASSWORD, "xyz123456"));

        // 这是「改密后强制重新登录」的唯一实现手段，必须被调用
        verify(tokenStore).bumpPasswordVersion(anyLong());
        verify(sysUserMapper).updateById(any(SysUser.class));
    }

    /* ================================================================== */
    /* 登出（F-01 修复后：仅按 jti 拉黑，不再 bumpPasswordVersion）             */
    /* ================================================================== */

    /**
     * F-01 修复后的正确语义：logout 只拉黑当前 accessToken 的 jti 与 refreshToken 的 jti，
     * <b>不能</b> bump 该用户的密码版本 —— 那会让同账号其它设备上的会话被一并踢下线，
     * 与「按设备登出」的用户期望冲突。
     */
    @Test
    @DisplayName("登出：仅 blacklist 当前 accessToken / refreshToken 的 jti，不 bump 密码版本")
    void logoutShouldBlacklistByJtiOnlyNotBumpPasswordVersion() {
        loginAs(normalUser());
        given(tokenProvider.remainingSeconds(anyLong())).willReturn(1800L);

        authService.logout("refresh-token-string");

        // 核心变更断言：bumpPasswordVersion 不应被调用
        verify(tokenStore, never()).bumpPasswordVersion(anyLong());
        // 但当前 accessToken 的 jti 必然被拉黑，且 TTL > 0
        verify(tokenStore).blacklist(eq("jti-access"), eq(1800L));
    }

    /**
     * 登出同时传入 refreshToken 时，该 refreshToken 的 jti 也必须拉黑 —— 否则
     * 「refreshToken 7 天内仍可换发 accessToken」的盲区就关了不严。
     */
    @Test
    @DisplayName("登出：传入 refreshToken → 解析后把它的 jti 也拉黑")
    void logoutShouldBlacklistRefreshTokenJti() {
        loginAs(normalUser());
        TokenPayload refreshPayload = refreshPayload();
        given(tokenProvider.remainingSeconds(anyLong())).willReturn(1800L);
        given(tokenProvider.parse("refresh-token-string")).willReturn(refreshPayload);

        authService.logout("refresh-token-string");

        verify(tokenStore).blacklist(eq("jti-access"), eq(1800L));
        verify(tokenStore).blacklist(eq("jti-refresh"), eq(1800L));
    }

    /**
     * 登出时客户端没传 refreshToken（或已过期）必须<b>依然成功</b> —— 不能因为
     * 一个附属参数有问题就让用户「退不出去」。
     */
    @Test
    @DisplayName("登出：refreshToken 解析失败 → 登出依然成功，仅当前 accessToken 的 jti 被拉黑")
    void logoutShouldSucceedEvenWhenRefreshTokenUnparseable() {
        loginAs(normalUser());
        given(tokenProvider.remainingSeconds(anyLong())).willReturn(1800L);
        given(tokenProvider.parse("garbage-token"))
                .willThrow(new BusinessException(ResultCode.UNAUTHORIZED, "刷新令牌无效"));

        // 不能抛异常
        assertDoesNotThrow(() -> authService.logout("garbage-token"));

        verify(tokenStore).blacklist(eq("jti-access"), eq(1800L));
        // refreshToken 那一次 blacklist 不会发生（解析失败被吞）
        verify(tokenStore, times(1)).blacklist(anyString(), anyLong());
    }

    /* ================================================================== */
    /* 测试夹具                                                            */
    /* ================================================================== */

    private SysUser normalUser() {
        SysUser user = new SysUser();
        user.setId(10023L);
        user.setUsername("family001");
        user.setPassword(ENCODED_PASSWORD);
        user.setNickname("张三");
        user.setPhone("13812348888");
        user.setRole(RoleConstants.FAMILY);
        user.setStatus(AccountStatus.NORMAL);
        user.setNeedChangePassword(0);
        return user;
    }

    private LoginDTO loginDto() {
        LoginDTO dto = new LoginDTO();
        dto.setUsername("family001");
        dto.setPassword(RAW_PASSWORD);
        dto.setCaptchaKey("captcha-key");
        dto.setCaptchaCode("8F3K");
        return dto;
    }

    private RegisterDTO registerDto() {
        RegisterDTO dto = new RegisterDTO();
        dto.setUsername("zhangsan");
        dto.setPhone("13812348888");
        dto.setPassword(RAW_PASSWORD);
        dto.setNickname("张三");
        dto.setRole(RoleConstants.FAMILY);
        dto.setCaptchaKey("captcha-key");
        dto.setCaptchaCode("8F3K");
        return dto;
    }

    private ChangePasswordDTO changePasswordDto(String oldPassword, String newPassword) {
        ChangePasswordDTO dto = new ChangePasswordDTO();
        dto.setOldPassword(oldPassword);
        dto.setNewPassword(newPassword);
        dto.setConfirmPassword(newPassword);
        return dto;
    }

    private TokenPayload refreshPayload() {
        return new TokenPayload(10023L, "family001", RoleConstants.FAMILY,
                JwtTokenProvider.TYPE_REFRESH, "jti-refresh", 0,
                System.currentTimeMillis(), System.currentTimeMillis() + 60000);
    }

    /** 把登录态放进 SecurityContext，模拟「已登录用户调用」 */
    private void loginAs(SysUser user) {
        LoginUser loginUser = new LoginUser(user.getId(), user.getUsername(), user.getRole(), 0,
                "jti-access", System.currentTimeMillis() + 60000);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, loginUser.authorities()));
    }

    private void assertLoginLog(String expectedStatus, String expectedReason) {
        ArgumentCaptor<SysLoginLog> captor = ArgumentCaptor.forClass(SysLoginLog.class);
        verify(sysLoginLogMapper).insert(captor.capture());
        SysLoginLog saved = captor.getValue();
        assertNotNull(saved.getLoginTime());
        assertEquals(expectedStatus, saved.getStatus());
        assertEquals(expectedReason, saved.getFailReason());
    }
}

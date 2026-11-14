package org.company.nianglin.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.company.nianglin.constant.AccountStatus;
import org.company.nianglin.constant.RedisKeyConstants;
import org.company.nianglin.constant.RoleConstants;
import org.company.nianglin.entity.SysUser;
import org.company.nianglin.mapper.SysUserMapper;
import org.company.nianglin.security.JwtTokenProvider;
import org.company.nianglin.security.TokenStore;
import org.company.nianglin.service.sms.SmsSender;
import org.company.nianglin.support.TestTokens;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 自助找回密码（V4 · P1-1）接口级测试。
 *
 * <p>覆盖 10 条核心语义 + 公开性 / 老人只读拦截矩阵。所有断言围绕<b>安全语义</b>：
 * 防枚举、频控、一次性验证码、失败作废、改密后踢全端。</p>
 *
 * <p>测试用临时注册账号（{@link Transactional} 回滚），绝不改种子账号密码；
 * Redis 键在同一事务外，用 {@code sms:*} 前缀统一清理。</p>
 *
 * @author 银龄伴诊团队
 * @since V4 · P1-1
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@DisplayName("自助找回密码：防枚举 / 频控 / 验证码 / 踢全端")
class AuthForgotPasswordTest {

    private static final String OLD_PASSWORD = "OldPass123";
    private static final String NEW_PASSWORD = "NewPass456";
    private static final String SMS_CODE_MESSAGE = "验证码已生成";
    private static final String PASSWORD_RESET_MESSAGE = "密码已重置，请重新登录";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SysUserMapper sysUserMapper;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private TokenStore tokenStore;

    @MockBean
    private SmsSender smsSender;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private String phone;
    private String username;
    private Long userId;

    @BeforeEach
    void setUp() {
        clearSmsKeys();
        phone = freshPhone();
        username = "fp" + phone.substring(3);
        SysUser user = new SysUser();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(OLD_PASSWORD));
        user.setNickname("找回测试");
        user.setPhone(phone);
        user.setRole(RoleConstants.FAMILY);
        user.setStatus(AccountStatus.NORMAL);
        user.setNeedChangePassword(0);
        sysUserMapper.insert(user);
        userId = user.getId();
        assertNotNull(userId);
    }

    @AfterEach
    void tearDown() {
        clearSmsKeys();
        if (userId != null) {
            redisTemplate.delete(RedisKeyConstants.passwordVersion(userId));
        }
    }

    /* ================================================================== */
    /* ① – ④ 发码与频控                                                     */
    /* ================================================================== */

    @Test
    @DisplayName("① 发码：200 + data 为 6 位码，与 Redis 一致且 TTL 为正，绝不调短信通道")
    void shouldStoreCodeForRegisteredPhone() throws Exception {
        mockMvc.perform(post("/api/auth/sms-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(smsBody(phone)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value(SMS_CODE_MESSAGE))
                .andExpect(jsonPath("$.data").isString())
                .andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.matchesPattern("\\d{6}")));

        String code = redisTemplate.opsForValue().get(RedisKeyConstants.smsResetCode(phone));
        assertNotNull(code, "发码后 Redis 必须存在验证码");
        assertTrue(code.matches("\\d{6}"), "验证码必须是 6 位数字");
        assertTrue(redisTemplate.getExpire(RedisKeyConstants.smsResetCode(phone)) > 0L,
                "验证码必须带 TTL");

        // 回归护栏：找回密码已去短信化（ADR-0021），绝不能再调用短信通道
        verify(smsSender, never()).send(any(), any(), any());
    }

    @Test
    @DisplayName("② 未注册手机号发码：与已注册结构同构（同 200/同文案/各自 6 位 data），且不调短信通道")
    void shouldReturnIdenticalBodyForUnregisteredPhone() throws Exception {
        String ghostPhone = freshPhone();
        MvcResult registered = mockMvc.perform(post("/api/auth/sms-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(smsBody(phone)))
                .andExpect(status().isOk())
                .andReturn();
        MvcResult unregistered = mockMvc.perform(post("/api/auth/sms-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(smsBody(ghostPhone)))
                .andExpect(status().isOk())
                .andReturn();

        // data 里是随机码，两次响应不可能字节级相等 —— 比结构：同 code、同 message、
        // 双方 data 都是 6 位数字（防枚举靠「人人都拿得到码」自动成立）
        JsonNode reg = objectMapper.readTree(registered.getResponse().getContentAsString());
        JsonNode ghost = objectMapper.readTree(unregistered.getResponse().getContentAsString());
        assertEquals(reg.get("code").asInt(), ghost.get("code").asInt(),
                "已注册 / 未注册的业务码必须一致");
        assertEquals(reg.get("message").asText(), ghost.get("message").asText(),
                "已注册 / 未注册的文案必须一致（防枚举）");
        assertTrue(reg.get("data").asText().matches("\\d{6}"), "已注册响应 data 必须是 6 位码");
        assertTrue(ghost.get("data").asText().matches("\\d{6}"), "未注册响应 data 必须是 6 位码");

        verify(smsSender, never()).send(any(), any(), any());
    }

    @Test
    @DisplayName("③ 同手机号 60 秒内第二次：1009")
    void shouldRejectWithinSixtySeconds() throws Exception {
        mockMvc.perform(post("/api/auth/sms-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(smsBody(phone)))
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(post("/api/auth/sms-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(smsBody(phone)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1009));

        verify(smsSender, never()).send(any(), any(), any());
    }

    @Test
    @DisplayName("④ 同手机号每日上限：第 11 次返回 1009")
    void shouldRejectAfterDailyLimit() throws Exception {
        for (int i = 0; i < 10; i++) {
            // 手动清掉 60 秒标记，才能把日上限单独测出来
            redisTemplate.delete(RedisKeyConstants.smsSendMarker(phone));
            mockMvc.perform(post("/api/auth/sms-code")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(smsBody(phone)))
                    .andExpect(jsonPath("$.code").value(200));
        }
        redisTemplate.delete(RedisKeyConstants.smsSendMarker(phone));
        mockMvc.perform(post("/api/auth/sms-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(smsBody(phone)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1009));

        verify(smsSender, never()).send(any(), any(), any());
    }

    /* ================================================================== */
    /* ⑤ – ⑦ 验证码错误 / 过期 / 错满作废                                    */
    /* ================================================================== */

    @Test
    @DisplayName("⑤ 验证码错误：1008，且密码未被修改")
    void shouldRejectWrongCode() throws Exception {
        String code = sendAndReadCode(phone);
        String wrong = code.equals("000000") ? "111111" : "000000";

        mockMvc.perform(post("/api/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(forgotBody(phone, wrong)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1008));

        SysUser unchanged = sysUserMapper.selectById(userId);
        assertTrue(passwordEncoder.matches(OLD_PASSWORD, unchanged.getPassword()),
                "验证码错误时不得修改密码");
    }

    @Test
    @DisplayName("⑥ 验证码过期（键已删）：1008")
    void shouldRejectExpiredCode() throws Exception {
        String code = sendAndReadCode(phone);
        redisTemplate.delete(RedisKeyConstants.smsResetCode(phone));

        mockMvc.perform(post("/api/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(forgotBody(phone, code)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1008));
    }

    @Test
    @DisplayName("⑦ 连续错 5 次后键已删除：再输入正确码仍 1008")
    void shouldInvalidateCodeAfterFiveFailures() throws Exception {
        String code = sendAndReadCode(phone);
        String wrong = code.equals("000000") ? "111111" : "000000";

        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/api/auth/forgot-password")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(forgotBody(phone, wrong)))
                    .andExpect(jsonPath("$.code").value(1008));
        }

        assertNull(redisTemplate.opsForValue().get(RedisKeyConstants.smsResetCode(phone)),
                "错满 5 次必须删除验证码键");

        mockMvc.perform(post("/api/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(forgotBody(phone, code)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1008));
    }

    /* ================================================================== */
    /* ⑧ – ⑨ 成功改密 / 复用                                                */
    /* ================================================================== */

    @Test
    @DisplayName("⑧ 成功改密：BCrypt 更新 + pwd:version 递增 + 旧 token 立即失效")
    void shouldResetPasswordAndInvalidateOldTokens() throws Exception {
        String oldToken = TestTokens.bearer(tokenProvider, tokenStore, userId,
                RoleConstants.FAMILY, "forgot-old");

        String code = sendAndReadCode(phone);
        mockMvc.perform(post("/api/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(forgotBody(phone, code)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value(PASSWORD_RESET_MESSAGE));

        SysUser reset = sysUserMapper.selectById(userId);
        assertTrue(reset.getPassword().startsWith("$2a$"), "密码必须以 BCrypt 存储");
        assertTrue(passwordEncoder.matches(NEW_PASSWORD, reset.getPassword()), "新密码必须可登录");
        assertEquals(0, reset.getNeedChangePassword(), "自助改密后需清掉强制改密标记");

        assertTrue(tokenStore.currentPasswordVersion(userId) > 0,
                "pwd:version 必须递增，才能让旧令牌失效");

        mockMvc.perform(get("/api/auth/me").header("Authorization", oldToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("⑨ 验证码成功后被复用：1008")
    void shouldRejectReusedCode() throws Exception {
        String code = sendAndReadCode(phone);
        mockMvc.perform(post("/api/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(forgotBody(phone, code)))
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(post("/api/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(forgotBody(phone, code)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1008));
    }

    /* ================================================================== */
    /* ⑩ 手机号不存在：与成功同构                                            */
    /* ================================================================== */

    @Test
    @DisplayName("⑩ 手机号不存在时 forgot-password 与成功响应完全同构，且不改任何用户")
    void shouldReturnIdenticalSuccessForUnregisteredPhone() throws Exception {
        // 先跑一次真实成功，拿到基准响应体
        String code = sendAndReadCode(phone);
        MvcResult success = mockMvc.perform(post("/api/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(forgotBody(phone, code)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();

        // 未注册手机号：发码阶段生成但不下发，测试直接读 Redis 拿到码
        String ghostPhone = freshPhone();
        mockMvc.perform(post("/api/auth/sms-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(smsBody(ghostPhone)))
                .andExpect(jsonPath("$.code").value(200));
        String ghostCode = redisTemplate.opsForValue().get(RedisKeyConstants.smsResetCode(ghostPhone));
        assertNotNull(ghostCode);

        MvcResult ghost = mockMvc.perform(post("/api/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(forgotBody(ghostPhone, ghostCode)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();

        assertEquals(success.getResponse().getContentAsString(),
                ghost.getResponse().getContentAsString(),
                "手机号不存在时的响应必须与成功完全同构（防枚举）");

        // 未注册手机号不产生任何用户；临时账号的密码确实被改了（证明成功路径生效）
        assertNull(sysUserMapper.selectOne(
                Wrappers.<SysUser>lambdaQuery().eq(SysUser::getPhone, ghostPhone)));
        assertTrue(passwordEncoder.matches(NEW_PASSWORD,
                sysUserMapper.selectById(userId).getPassword()));
    }

    /* ================================================================== */
    /* 公开性 / 老人只读拦截矩阵                                             */
    /* ================================================================== */

    @Test
    @DisplayName("公开性：匿名可达；非法 body 返回 400；ELDER 不被只读拦截器误拦")
    void shouldBePublicAndNotBlockedByElderInterceptor() throws Exception {
        // 参数格式错误 → 业务码 400（HTTP 仍 200）
        mockMvc.perform(post("/api/auth/sms-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));

        mockMvc.perform(post("/api/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"" + phone + "\",\"smsCode\":\"abc\",\"newPassword\":\"NewPass456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));

        mockMvc.perform(post("/api/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"" + phone + "\",\"smsCode\":\"123456\",\"newPassword\":\"abc\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));

        // ELDER 带合法令牌：短信端点必须放行（200），忘记密码端点不被 403 拦截（走到 1008）
        String elderToken = TestTokens.bearer(tokenProvider, tokenStore, 201L,
                RoleConstants.ELDER, "forgot-elder");

        mockMvc.perform(post("/api/auth/sms-code")
                        .header("Authorization", elderToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(smsBody(freshPhone())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(post("/api/auth/forgot-password")
                        .header("Authorization", elderToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(forgotBody(freshPhone(), "123456")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1008));
    }

    /* ================================================================== */
    /* 工具方法                                                            */
    /* ================================================================== */

    private String sendAndReadCode(String targetPhone) throws Exception {
        // 直接读响应里的 data —— 顺带验证「验证码回传」契约（ADR-0021）
        MvcResult result = mockMvc.perform(post("/api/auth/sms-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(smsBody(targetPhone)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();
        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
        assertNotNull(data, "发码响应的 data 必须携带验证码");
        String code = data.asText();
        assertTrue(code.matches("\\d{6}"), "响应 data 必须是 6 位数字验证码");
        return code;
    }

    private String smsBody(String targetPhone) throws Exception {
        return objectMapper.writeValueAsString(Map.of("phone", targetPhone));
    }

    private String forgotBody(String targetPhone, String code) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "phone", targetPhone,
                "smsCode", code,
                "newPassword", NEW_PASSWORD));
    }

    private void clearSmsKeys() {
        Set<String> keys = redisTemplate.keys("sms:*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    private static String freshPhone() {
        return "199" + String.format("%08d", ThreadLocalRandom.current().nextInt(100_000_000));
    }
}

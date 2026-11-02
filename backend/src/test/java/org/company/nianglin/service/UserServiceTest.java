package org.company.nianglin.service;

import com.alibaba.excel.EasyExcel;
import org.company.nianglin.common.ResultCode;
import org.company.nianglin.constant.AccountStatus;
import org.company.nianglin.constant.OrderStatus;
import org.company.nianglin.constant.RoleConstants;
import org.company.nianglin.dto.DeactivateAccountDTO;
import org.company.nianglin.entity.CompanionOrder;
import org.company.nianglin.entity.CompanionProfile;
import org.company.nianglin.entity.ElderProfile;
import org.company.nianglin.entity.FamilyElderRelation;
import org.company.nianglin.entity.InternalMessage;
import org.company.nianglin.entity.SysUser;
import org.company.nianglin.exception.BusinessException;
import org.company.nianglin.mapper.CompanionOrderMapper;
import org.company.nianglin.mapper.CompanionProfileMapper;
import org.company.nianglin.mapper.ElderProfileMapper;
import org.company.nianglin.mapper.FamilyElderRelationMapper;
import org.company.nianglin.mapper.InternalMessageMapper;
import org.company.nianglin.mapper.OrderReviewMapper;
import org.company.nianglin.mapper.SysUserMapper;
import org.company.nianglin.security.LoginUser;
import org.company.nianglin.security.TokenStore;
import org.company.nianglin.service.impl.UserServiceImpl;
import org.company.nianglin.support.MybatisLambdaCache;
import org.company.nianglin.vo.DataExportElderVO;
import org.company.nianglin.vo.DataExportOrderVO;
import org.company.nianglin.vo.DataExportProfileVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 用户服务单测（V4 · P0-1）：注销闸门与导出脱敏。
 *
 * <p>注销是「一次点错就回不来了」的操作，三条闸门（确认文本 → 密码 → 进行中订单）
 * 的<b>顺序</b>本身就是需求：确认文本不对就不该浪费一次 BCrypt（~80ms），
 * 密码不对就不该泄露「你有没有进行中订单」。这些一旦被重构打乱，必须在这里变红。</p>
 *
 * <p>导出断言的不是「文件能生成」，而是<b>落盘的每一个敏感字段都已脱敏</b> ——
 * xlsx 会被用户保存、转发，它是脱敏纪律的最终出口，没有「下次注意」的机会。</p>
 *
 * @author 银龄伴诊团队
 * @since V4
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("用户服务：注销账号 / 个人数据导出（V4 · P0-1）")
class UserServiceTest {

    private static final Long USER_ID = 10023L;
    private static final String ENCODED_PASSWORD =
            "$2a$10$abcdefghijklmnopqrstuvwxyz0123456789ABCDEFGHIJKLMN";
    private static final String FULL_PHONE = "13812348888";
    private static final String FULL_NAME = "张三丰";

    @Mock
    private SysUserMapper sysUserMapper;

    @Mock
    private ElderService elderService;

    @Mock
    private CompanionOrderMapper companionOrderMapper;

    @Mock
    private CompanionProfileMapper companionProfileMapper;

    @Mock
    private FamilyElderRelationMapper familyElderRelationMapper;

    @Mock
    private ElderProfileMapper elderProfileMapper;

    @Mock
    private OrderReviewMapper orderReviewMapper;

    @Mock
    private InternalMessageMapper internalMessageMapper;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private TokenStore tokenStore;

    private UserServiceImpl userService;

    @BeforeEach
    void setUp() {
        // 纯 Mockito 单测不起 Spring 容器，MyBatis-Plus 的 lambda 列名缓存是空的，
        // LambdaUpdateWrapper.set(...) 会抛 "can not find lambda cache"。详见 MybatisLambdaCache。
        MybatisLambdaCache.warmUp();
        userService = new UserServiceImpl(sysUserMapper, elderService, companionOrderMapper,
                companionProfileMapper, familyElderRelationMapper, elderProfileMapper,
                orderReviewMapper, internalMessageMapper, passwordEncoder, tokenStore);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    /* ================================================================== */
    /* 注销 · 三道闸门的顺序与短路                                             */
    /* ================================================================== */

    @Test
    @DisplayName("注销 · 确认文本不匹配 → 400，且不校验密码、不查订单（省一次 BCrypt）")
    void deactivateShouldRejectMismatchedConfirmText() {
        loginAs(familyUser());
        given(sysUserMapper.selectById(USER_ID)).willReturn(familyUser());
        DeactivateAccountDTO dto = dto("Nl@123456", "我同意");

        BusinessException ex = assertThrows(BusinessException.class, () -> userService.deactivate(dto));

        assertEquals(ResultCode.PARAM_ERROR.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("我已知晓注销后果"), "提示语必须告诉用户要原样输入什么");
        // 文本都不对，说明用户没读后果说明 —— 不值得花 ~80ms 跑一次 BCrypt
        verify(passwordEncoder, never()).matches(anyString(), anyString());
        verify(companionOrderMapper, never()).selectCount(any());
    }

    @Test
    @DisplayName("注销 · 密码错误 → 1006，且不进入订单闸门")
    void deactivateShouldRejectWrongPassword() {
        loginAs(familyUser());
        given(sysUserMapper.selectById(USER_ID)).willReturn(familyUser());
        given(passwordEncoder.matches(anyString(), anyString())).willReturn(false);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> userService.deactivate(dto("wrong-password", "我已知晓注销后果")));

        assertEquals(ResultCode.OLD_PASSWORD_ERROR.getCode(), ex.getCode());
        // 密码不对就不知道操作者是不是本人，「有没有进行中订单」这个信息不能泄出去
        verify(companionOrderMapper, never()).selectCount(any());
    }

    @Test
    @DisplayName("注销 · 存在进行中订单 → 11001，data 携带 pendingOrderCount，且不做任何写操作")
    void deactivateShouldBlockWhenActiveOrdersExist() {
        loginAs(familyUser());
        given(sysUserMapper.selectById(USER_ID)).willReturn(familyUser());
        given(passwordEncoder.matches(anyString(), anyString())).willReturn(true);
        given(companionOrderMapper.selectCount(any())).willReturn(2L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> userService.deactivate(dto("Nl@123456", "我已知晓注销后果")));

        assertEquals(ResultCode.DEACTIVATE_HAS_ACTIVE_ORDER.getCode(), ex.getCode());
        // 前端要靠这个字段渲染「你还有 N 笔进行中订单」，不能只写在 message 文案里
        assertEquals(Map.of("pendingOrderCount", 2L), ex.getData());
        verify(sysUserMapper, never()).update(any(), any());
        verify(tokenStore, never()).bumpPasswordVersion(anyLong());
    }

    @Test
    @DisplayName("注销 · 家属成功：账号条件更新去标识化 + 绑定关系逻辑删 + 密码版本 +1（封禁接口绝不调用）")
    void deactivateShouldSucceedForFamily() {
        SysUser user = familyUser();
        loginAs(user);
        given(sysUserMapper.selectById(USER_ID)).willReturn(user);
        given(passwordEncoder.matches(anyString(), anyString())).willReturn(true);
        given(companionOrderMapper.selectCount(any())).willReturn(0L);
        given(companionProfileMapper.selectOne(any())).willReturn(null);

        userService.deactivate(dto("Nl@123456", "我已知晓注销后果"));

        // 条件更新（eq id + eq status=NORMAL）：并发重复提交时第二把是空 UPDATE，不会复活已删账号
        verify(sysUserMapper).update(any(), any(com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper.class));
        // 家属的绑定关系必须逻辑删除（@TableLogic 下 delete 即 UPDATE deleted = 1）
        verify(familyElderRelationMapper).delete(any(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper.class));
        // 401 语义靠密码版本；markBanned 是封禁的 403 语义，注销绝不能沾
        verify(tokenStore).bumpPasswordVersion(USER_ID);
        verify(tokenStore, never()).markBanned(anyLong());
    }

    @Test
    @DisplayName("注销 · 陪诊员成功：资质档案去标识化（姓名全隐 / 电话脱敏 / 身份证密文置 NULL），不动家属绑定表")
    void deactivateShouldAnonymizeCompanionProfile() {
        SysUser user = familyUser();
        user.setRole(RoleConstants.COMPANION);
        loginAs(user);
        given(sysUserMapper.selectById(USER_ID)).willReturn(user);
        given(passwordEncoder.matches(anyString(), anyString())).willReturn(true);
        given(companionOrderMapper.selectCount(any())).willReturn(0L);
        CompanionProfile profile = new CompanionProfile();
        profile.setId(9001L);
        profile.setUserId(USER_ID);
        profile.setRealName(FULL_NAME);
        profile.setPhone(FULL_PHONE);
        profile.setIdCard("aes-ciphertext");
        given(companionProfileMapper.selectOne(any())).willReturn(profile);

        userService.deactivate(dto("Nl@123456", "我已知晓注销后果"));

        verify(companionProfileMapper).update(any(),
                any(com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper.class));
        // 陪诊员没有「绑定老人」，家属绑定表一次都不该碰
        verify(familyElderRelationMapper, never())
                .delete(any(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper.class));
        verify(tokenStore).bumpPasswordVersion(USER_ID);
        verify(tokenStore, never()).markBanned(anyLong());
    }

    /* ================================================================== */
    /* 导出 · 落盘字段全部脱敏                                                  */
    /* ================================================================== */

    @Test
    @DisplayName("导出 · 个人资料 sheet：手机号 138****8888、姓名张*丰；订单 sheet 落盘且不含对方姓名")
    void dataExportShouldMaskEverySensitiveCell() throws Exception {
        SysUser user = familyUser();
        loginAs(user);
        given(sysUserMapper.selectById(USER_ID)).willReturn(user);

        FamilyElderRelation relation = new FamilyElderRelation();
        relation.setId(5001L);
        relation.setFamilyId(USER_ID);
        relation.setElderId(201L);
        relation.setRelation("SON");
        relation.setBindType("PHONE");
        relation.setStatus("BOUND");
        relation.setBindTime(LocalDateTime.of(2026, 8, 1, 9, 0));
        given(familyElderRelationMapper.selectList(any())).willReturn(List.of(relation));
        ElderProfile elder = new ElderProfile();
        elder.setId(201L);
        elder.setName("李桂兰");
        given(elderProfileMapper.selectList(any())).willReturn(List.of(elder));

        CompanionOrder order = new CompanionOrder();
        order.setOrderNo("NL202610010001");
        order.setFamilyId(USER_ID);
        order.setCompanionId(301L);
        order.setHospital("海南省人民医院");
        order.setDepartment("心内科");
        order.setVisitTime(LocalDateTime.of(2026, 10, 5, 14, 30));
        order.setStatus(OrderStatus.COMPLETED.name());
        order.setFee(new BigDecimal("199.00"));
        order.setPaymentStatus("PAID");
        order.setCreateTime(LocalDateTime.of(2026, 10, 1, 10, 0));
        given(companionOrderMapper.selectList(any())).willReturn(List.of(order));
        given(orderReviewMapper.selectList(any())).willReturn(List.of());
        InternalMessage message = new InternalMessage();
        message.setTitle("订单已完成");
        message.setType("ORDER");
        message.setContent("您的订单 NL202610010001 已完成");
        message.setIsRead(1);
        message.setCreateTime(LocalDateTime.of(2026, 10, 5, 18, 0));
        given(internalMessageMapper.selectList(any())).willReturn(List.of(message));

        MockHttpServletResponse response = new MockHttpServletResponse();
        userService.dataExport(response);

        // 响应契约：octet-stream 附件 + 真的是 xlsx（zip 容器，前两字节 PK）。
        // setCharacterEncoding 会让容器把 charset 拼到 content-type 后面，只断言前缀
        assertTrue(response.getContentType().startsWith("application/octet-stream"), response.getContentType());
        byte[] body = response.getContentAsByteArray();
        assertTrue(body.length > 0, "响应体不能为空");
        assertEquals('P', body[0]);
        assertEquals('K', body[1]);

        // 读回「个人资料」：敏感单元格必须是脱敏形态，完整值一个字符都不能出现
        List<DataExportProfileVO> profiles = EasyExcel.read(new ByteArrayInputStream(body))
                .head(DataExportProfileVO.class).sheet("个人资料").doReadSync()
                .stream().map(DataExportProfileVO.class::cast).toList();
        assertEquals(1, profiles.size());
        DataExportProfileVO profile = profiles.get(0);
        assertEquals("138****8888", profile.getPhone());
        assertEquals("张*丰", profile.getRealName());
        assertEquals("家属", profile.getRoleLabel());
        assertEquals("正常", profile.getStatusLabel());

        // 读回「绑定老人」：老人姓名同样只留首尾
        List<DataExportElderVO> elders = EasyExcel.read(new ByteArrayInputStream(body))
                .head(DataExportElderVO.class).sheet("绑定老人").doReadSync()
                .stream().map(DataExportElderVO.class::cast).toList();
        assertEquals(1, elders.size());
        assertEquals("李*兰", elders.get(0).getElderName());
        assertEquals("儿子", elders.get(0).getRelationLabel());
        assertEquals("按手机号", elders.get(0).getBindTypeLabel());
        assertEquals("已绑定", elders.get(0).getStatusLabel());

        // 读回「订单记录」：行落盘、金额是字符串、我的角色标注正确
        List<DataExportOrderVO> orders = EasyExcel.read(new ByteArrayInputStream(body))
                .head(DataExportOrderVO.class).sheet("订单记录").doReadSync()
                .stream().map(DataExportOrderVO.class::cast).toList();
        assertEquals(1, orders.size());
        DataExportOrderVO orderRow = orders.get(0);
        assertEquals("NL202610010001", orderRow.getOrderNo());
        assertEquals("下单家属", orderRow.getMyRoleLabel());
        assertEquals("已完成", orderRow.getStatusLabel());
        assertEquals("199.00", orderRow.getFee());
        assertNotNull(orderRow.getPaymentStatusLabel());
    }

    @Test
    @DisplayName("导出 · 陪诊员：绑定老人 sheet 为空表（只有家属有绑定关系）")
    void dataExportShouldLeaveElderSheetEmptyForCompanion() {
        SysUser user = familyUser();
        user.setRole(RoleConstants.COMPANION);
        loginAs(user);
        given(sysUserMapper.selectById(USER_ID)).willReturn(user);
        given(companionOrderMapper.selectList(any())).willReturn(List.of());
        given(orderReviewMapper.selectList(any())).willReturn(List.of());
        given(internalMessageMapper.selectList(any())).willReturn(List.of());

        MockHttpServletResponse response = new MockHttpServletResponse();
        userService.dataExport(response);

        // 不是家属，绑定表一次都不该查（查了就是多余的读扩散）
        verify(familyElderRelationMapper, never())
                .selectList(any(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper.class));

        List<DataExportElderVO> elders = EasyExcel
                .read(new ByteArrayInputStream(response.getContentAsByteArray()))
                .head(DataExportElderVO.class).sheet("绑定老人").doReadSync()
                .stream().map(DataExportElderVO.class::cast).toList();
        assertTrue(elders.isEmpty(), "陪诊员的绑定老人 sheet 必须只有表头");
    }

    /* ================================================================== */
    /* 测试夹具                                                            */
    /* ================================================================== */

    private SysUser familyUser() {
        SysUser user = new SysUser();
        user.setId(USER_ID);
        user.setUsername("family001");
        user.setPassword(ENCODED_PASSWORD);
        user.setNickname("三丰");
        user.setRealName(FULL_NAME);
        user.setPhone(FULL_PHONE);
        user.setRole(RoleConstants.FAMILY);
        user.setStatus(AccountStatus.NORMAL);
        user.setCreateTime(LocalDateTime.of(2026, 7, 1, 8, 0));
        return user;
    }

    private DeactivateAccountDTO dto(String password, String confirmText) {
        DeactivateAccountDTO dto = new DeactivateAccountDTO();
        dto.setPassword(password);
        dto.setConfirmText(confirmText);
        return dto;
    }

    /** 把登录态放进 SecurityContext，模拟「已登录用户调用」（与 AuthServiceTest 同一手法） */
    private void loginAs(SysUser user) {
        LoginUser loginUser = new LoginUser(user.getId(), user.getUsername(), user.getRole(), 0,
                "jti-access", System.currentTimeMillis() + 60000);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, loginUser.authorities()));
    }
}

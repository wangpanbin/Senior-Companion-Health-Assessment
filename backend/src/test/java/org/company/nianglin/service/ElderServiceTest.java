package org.company.nianglin.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.company.nianglin.common.PageResult;
import org.company.nianglin.common.ResultCode;
import org.company.nianglin.constant.BindStatus;
import org.company.nianglin.constant.RoleConstants;
import org.company.nianglin.dto.ElderBindDTO;
import org.company.nianglin.dto.ElderCreateDTO;
import org.company.nianglin.dto.ElderQuery;
import org.company.nianglin.dto.ElderUpdateDTO;
import org.company.nianglin.entity.CompanionOrder;
import org.company.nianglin.entity.ElderProfile;
import org.company.nianglin.entity.FamilyElderRelation;
import org.company.nianglin.entity.SysUser;
import org.company.nianglin.exception.BusinessException;
import org.company.nianglin.mapper.CompanionOrderMapper;
import org.company.nianglin.mapper.ElderProfileMapper;
import org.company.nianglin.mapper.FamilyElderRelationMapper;
import org.company.nianglin.mapper.SysUserMapper;
import org.company.nianglin.security.LoginUser;
import org.company.nianglin.security.SecurityProperties;
import org.company.nianglin.service.impl.ElderServiceImpl;
import org.company.nianglin.util.AesUtil;
import org.company.nianglin.util.MaskUtil;
import org.company.nianglin.vo.ElderVO;
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

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 老人档案服务单测。
 *
 * <p>重点不在「增删改查能不能跑通」，而在<b>归属校验与脱敏这两件事有没有被绕过</b>：
 * 越权读、越权写、密文落库、脱敏出口、进行中订单拦截。这几条一旦回归，
 * 后果分别是隐私泄露、数据被改、合规红线和业务数据损坏。</p>
 *
 * @author 银龄伴诊团队
 * @since M3
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("老人档案服务：归属校验 / 脱敏 / 绑定解绑")
class ElderServiceTest {

    private static final String AES_KEY = "unit-test-id-card-key";
    private static final String PLAIN_ID_CARD = "460101194803120011";
    private static final String PLAIN_ADDRESS = "海南省海口市美兰区人民大道12号3栋501";

    /** 当前登录家属 */
    private static final Long FAMILY_ID = 101L;
    private static final Long ELDER_ID = 401L;
    /** 另一个家属，用于越权用例 */
    private static final Long OTHER_FAMILY_ID = 102L;

    @Mock
    private ElderProfileMapper elderProfileMapper;

    @Mock
    private FamilyElderRelationMapper relationMapper;

    @Mock
    private SysUserMapper sysUserMapper;

    @Mock
    private CompanionOrderMapper companionOrderMapper;

    private ElderServiceImpl elderService;

    @BeforeEach
    void setUp() {
        // ⚠️ 必须手工初始化 TableInfo。LambdaUpdateWrapper 的 set(...) 会立刻把
        // 方法引用翻译成列名，而翻译依赖 MyBatis-Plus 的 TableInfo 缓存；
        // 纯 Mockito 单测不起 Spring 容器，缓存是空的，会抛
        // “MybatisPlus can not find lambda cache for this entity”。
        //
        // 这个 bug 极其隐蔽：跑全量测试时，只要前面有 @SpringBootTest 类跑过，
        // 缓存已经建好，本类就"通过"了 —— 一次完全依赖测试执行顺序的假绿。
        // 把缓存准备好，本类才能单独运行。
        initTableInfo(ElderProfile.class);

        SecurityProperties securityProperties = new SecurityProperties();
        securityProperties.setIdCardKey(AES_KEY);
        elderService = new ElderServiceImpl(elderProfileMapper, relationMapper, sysUserMapper,
                companionOrderMapper, securityProperties);
    }

    /** 为指定实体建立 MyBatis-Plus 的 lambda 列名缓存（纯单测环境下的必要前置） */
    private static void initTableInfo(Class<?> entityClass) {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), entityClass);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    /* ================================================================== */
    /* 列表                                                                */
    /* ================================================================== */

    @Test
    @DisplayName("列表：没有绑定老人时直接返回空分页，不去查档案表")
    void pageShouldReturnEmptyWithoutQueryingElderTable() {
        loginAs(RoleConstants.FAMILY, FAMILY_ID);
        given(relationMapper.selectList(any())).willReturn(List.of());

        PageResult<ElderVO> result = elderService.page(new ElderQuery());

        assertEquals(0L, result.getTotal());
        assertTrue(result.getRecords().isEmpty());
        // records 必须是空列表而不是 null —— 前端不需要为「没数据」写两套判断
        assertNotNull(result.getRecords());
        // MyBatis-Plus 的 in() 传空集合会生成 `IN ()` 让 MySQL 语法报错，
        // 所以这里必须提前返回，绝不能把空集合带进 SQL
        verify(elderProfileMapper, never()).selectPage(any(), any());
    }

    @Test
    @DisplayName("列表：姓名脱敏、不含身份证与地址")
    void pageShouldMaskSensitiveFields() {
        loginAs(RoleConstants.FAMILY, FAMILY_ID);
        given(relationMapper.selectList(any())).willReturn(List.of(relation(ELDER_ID, BindStatus.BOUND)));
        given(elderProfileMapper.selectPage(any(), any())).willReturn(pageOf(elderProfile()));

        ElderVO vo = elderService.page(new ElderQuery()).getRecords().get(0);

        assertEquals("张*海", vo.getName());
        assertEquals("139****2222", vo.getPhone());
        // 列表页绝不返回这两项
        assertNull(vo.getIdCard());
        assertNull(vo.getAddress());
        assertNull(vo.getMedicalHistory());
    }

    /* ================================================================== */
    /* 详情：归属 + 脱敏                                                    */
    /* ================================================================== */

    @Test
    @DisplayName("详情：档案不存在返回 2001")
    void detailShouldRejectWhenElderMissing() {
        loginAs(RoleConstants.FAMILY, FAMILY_ID);
        given(elderProfileMapper.selectById(ELDER_ID)).willReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> elderService.detail(ELDER_ID));

        assertEquals(ResultCode.ELDER_NOT_FOUND.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("详情：家属读别人绑定的老人返回 2006（越权用例）")
    void detailShouldRejectWhenFamilyNotOwner() {
        loginAs(RoleConstants.FAMILY, FAMILY_ID);
        given(elderProfileMapper.selectById(ELDER_ID)).willReturn(elderProfile());
        // 关系表里只有「另一个家属」的绑定记录
        given(relationMapper.selectList(any())).willReturn(List.of());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> elderService.detail(ELDER_ID));

        assertEquals(ResultCode.NO_PERMISSION_FOR_ELDER.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("详情：解绑过的关系不算有效绑定，同样返回 2006")
    void detailShouldRejectWhenRelationUnbound() {
        loginAs(RoleConstants.FAMILY, FAMILY_ID);
        given(elderProfileMapper.selectById(ELDER_ID)).willReturn(elderProfile());
        given(relationMapper.selectList(any())).willReturn(List.of(relation(ELDER_ID, BindStatus.UNBOUND)));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> elderService.detail(ELDER_ID));

        assertEquals(ResultCode.NO_PERMISSION_FOR_ELDER.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("详情：绑定人可见全名，身份证返回脱敏串，地址门牌打码")
    void detailShouldReturnFullNameAndMaskedFieldsForOwner() {
        loginAs(RoleConstants.FAMILY, FAMILY_ID);
        given(elderProfileMapper.selectById(ELDER_ID)).willReturn(elderProfile());
        given(relationMapper.selectList(any())).willReturn(List.of(relation(ELDER_ID, BindStatus.BOUND)));

        ElderVO vo = elderService.detail(ELDER_ID);

        assertEquals("张德海", vo.getName());
        assertEquals(MaskUtil.idCard(PLAIN_ID_CARD), vo.getIdCard());
        assertEquals("460101********0011", vo.getIdCard());
        assertEquals("海南省海口市美兰区人民大道***", vo.getAddress());
        assertEquals("138****8888", vo.getEmergencyPhone());
        // 病史属于「绑定家属可见」，要真的返回，不能因为谨慎而抹掉
        assertEquals("高血压、2型糖尿病，长期服药", vo.getMedicalHistory());
        assertEquals("儿子", vo.getRelation());
    }

    @Test
    @DisplayName("详情：管理员无需绑定关系即可读任意档案")
    void detailShouldAllowAdminWithoutRelation() {
        loginAs(RoleConstants.ADMIN, 1L);
        given(elderProfileMapper.selectById(ELDER_ID)).willReturn(elderProfile());

        ElderVO vo = elderService.detail(ELDER_ID);

        assertEquals("张德海", vo.getName());
        // 管理员没有「与老人关系」，不该去查关系表
        verify(relationMapper, never()).selectList(any());
        assertNull(vo.getRelation());
    }

    @Test
    @DisplayName("详情：老人可以读自己的档案")
    void detailShouldAllowElderReadingOwnProfile() {
        // 档案的 user_id = 201，登录的正是 201 号老人账号
        loginAs(RoleConstants.ELDER, 201L);
        given(elderProfileMapper.selectById(ELDER_ID)).willReturn(elderProfile());

        ElderVO vo = elderService.detail(ELDER_ID);

        assertEquals("张德海", vo.getName());
        verify(relationMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("详情：老人读别人的档案返回 2006（越权用例）")
    void detailShouldRejectElderReadingOthersProfile() {
        loginAs(RoleConstants.ELDER, 202L);
        given(elderProfileMapper.selectById(ELDER_ID)).willReturn(elderProfile());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> elderService.detail(ELDER_ID));

        assertEquals(ResultCode.NO_PERMISSION_FOR_ELDER.getCode(), ex.getCode());
    }

    /* ================================================================== */
    /* 新增                                                                */
    /* ================================================================== */

    @Test
    @DisplayName("新增：身份证加密落库（密文 != 明文），并自动建立绑定关系")
    void createShouldEncryptIdCardAndAutoBind() {
        loginAs(RoleConstants.FAMILY, FAMILY_ID);

        ElderCreateDTO dto = new ElderCreateDTO()
                .setName("张德海")
                .setGender("MALE")
                .setBirthDate(LocalDate.of(1948, 3, 12))
                .setIdCard(PLAIN_ID_CARD)
                .setPhone("13911112222")
                .setMobilityLevel("ASSIST");

        elderService.create(dto);

        ArgumentCaptor<ElderProfile> elderCaptor = ArgumentCaptor.forClass(ElderProfile.class);
        verify(elderProfileMapper).insert(elderCaptor.capture());
        ElderProfile saved = elderCaptor.getValue();

        // 合规红线：库里不能出现明文身份证号
        assertNotEquals(PLAIN_ID_CARD, saved.getIdCard());
        assertFalse(saved.getIdCard().contains(PLAIN_ID_CARD));
        assertEquals(PLAIN_ID_CARD, AesUtil.decrypt(saved.getIdCard(), AES_KEY));

        assertEquals(BindStatus.BOUND.name(), saved.getBindStatus());
        assertEquals(FAMILY_ID, saved.getCreateBy());
        assertEquals("MALE", saved.getGender());

        // 建档即绑定，否则家属刚建完档案在列表里看不到
        ArgumentCaptor<FamilyElderRelation> relCaptor =
                ArgumentCaptor.forClass(FamilyElderRelation.class);
        verify(relationMapper).insert(relCaptor.capture());
        FamilyElderRelation rel = relCaptor.getValue();
        assertEquals(FAMILY_ID, rel.getFamilyId());
        assertEquals(BindStatus.BOUND.name(), rel.getStatus());
        assertEquals(1, rel.getIsDefault());
    }

    @Test
    @DisplayName("新增：性别统一存大写，避免 male 混进枚举列")
    void createShouldNormalizeEnumToUpperCase() {
        loginAs(RoleConstants.FAMILY, FAMILY_ID);

        elderService.create(new ElderCreateDTO()
                .setName("李桂英")
                .setGender("female")
                .setBirthDate(LocalDate.of(1950, 1, 1)));

        ArgumentCaptor<ElderProfile> captor = ArgumentCaptor.forClass(ElderProfile.class);
        verify(elderProfileMapper).insert(captor.capture());
        assertEquals("FEMALE", captor.getValue().getGender());
        // 没填身份证时不写空串，直接留 null（列本身可空）
        assertNull(captor.getValue().getIdCard());
    }

    /* ================================================================== */
    /* 修改                                                                */
    /* ================================================================== */

    @Test
    @DisplayName("修改：不是绑定人返回 2006，且一次 UPDATE 都不执行")
    void updateShouldRejectWhenNotOwner() {
        loginAs(RoleConstants.FAMILY, OTHER_FAMILY_ID);
        given(elderProfileMapper.selectById(ELDER_ID)).willReturn(elderProfile());
        given(relationMapper.selectList(any())).willReturn(List.of());

        ElderUpdateDTO dto = new ElderUpdateDTO().setAddress("新地址");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> elderService.update(ELDER_ID, dto));

        assertEquals(ResultCode.NO_PERMISSION_FOR_ELDER.getCode(), ex.getCode());
        // 越权请求绝不能碰到数据
        verify(elderProfileMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("修改：字段全不传时不执行 UPDATE（避免把 update_time 刷脏）")
    void updateShouldSkipWhenNothingProvided() {
        loginAs(RoleConstants.FAMILY, FAMILY_ID);
        given(elderProfileMapper.selectById(ELDER_ID)).willReturn(elderProfile());
        given(relationMapper.selectList(any())).willReturn(List.of(relation(ELDER_ID, BindStatus.BOUND)));

        elderService.update(ELDER_ID, new ElderUpdateDTO());

        verify(elderProfileMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("修改：传空串表示清空该字段，且必须走 set(null) 而不是 updateById")
    void updateShouldClearFieldOnBlankValue() {
        loginAs(RoleConstants.FAMILY, FAMILY_ID);
        given(elderProfileMapper.selectById(ELDER_ID)).willReturn(elderProfile());
        given(relationMapper.selectList(any())).willReturn(List.of(relation(ELDER_ID, BindStatus.BOUND)));

        elderService.update(ELDER_ID, new ElderUpdateDTO().setAddress("   "));

        LambdaUpdateWrapper<ElderProfile> update = captureUpdateWrapper();
        // updateById 会跳过 null 字段，「清空地址」这件事它根本做不到 ——
        // 只有 update(null, wrapper) + set(column, null) 才能把列置空
        assertTrue(update.getSqlSet().contains("address"),
                "SET 子句里应包含 address：" + update.getSqlSet());
        // 注意 any() 在这里会编译歧义：BaseMapper 同时有 updateById(T) 与
        // updateById(Collection<T>)，必须显式给出类型
        verify(elderProfileMapper, never()).updateById(any(ElderProfile.class));
    }

    @Test
    @DisplayName("修改：姓名传空串报参数错误（库里是 NOT NULL 列）")
    void updateShouldRejectBlankName() {
        loginAs(RoleConstants.FAMILY, FAMILY_ID);
        given(elderProfileMapper.selectById(ELDER_ID)).willReturn(elderProfile());
        given(relationMapper.selectList(any())).willReturn(List.of(relation(ELDER_ID, BindStatus.BOUND)));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> elderService.update(ELDER_ID, new ElderUpdateDTO().setName("  ")));

        assertEquals(ResultCode.PARAM_ERROR.getCode(), ex.getCode());
        verify(elderProfileMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("修改：重新加密身份证，参数里只有密文、没有明文")
    void updateShouldReEncryptIdCard() {
        loginAs(RoleConstants.FAMILY, FAMILY_ID);
        given(elderProfileMapper.selectById(ELDER_ID)).willReturn(elderProfile());
        given(relationMapper.selectList(any())).willReturn(List.of(relation(ELDER_ID, BindStatus.BOUND)));

        String newIdCard = "460101195001010022";
        elderService.update(ELDER_ID, new ElderUpdateDTO().setIdCard(newIdCard));

        LambdaUpdateWrapper<ElderProfile> update = captureUpdateWrapper();

        // 只断言「SQL 里没有明文」是没有意义的 —— MyBatis 的参数是 #{...} 占位符，
        // 明文无论如何都不会出现在 SQL 字符串里。真正要看的是参数值本身：
        // 它必须能被密钥解回原文（说明是密文），且不能等于原文（说明确实加密了）
        List<Object> values = List.copyOf(update.getParamNameValuePairs().values());
        assertFalse(values.contains(newIdCard), "参数中不允许出现明文身份证号");
        assertTrue(values.stream()
                        .filter(String.class::isInstance)
                        .map(String.class::cast)
                        .anyMatch(v -> newIdCard.equals(AesUtil.decrypt(v, AES_KEY))),
                "参数中应存在可用密钥解出原身份证号的密文");
    }

    /* ================================================================== */
    /* 删除 / 解绑：进行中订单是硬闸门                                       */
    /* ================================================================== */

    @Test
    @DisplayName("删除：有进行中订单返回 409，档案不被删除")
    void deleteShouldRejectWhenActiveOrderExists() {
        loginAs(RoleConstants.FAMILY, FAMILY_ID);
        given(elderProfileMapper.selectById(ELDER_ID)).willReturn(elderProfile());
        given(relationMapper.selectList(any())).willReturn(List.of(relation(ELDER_ID, BindStatus.BOUND)));
        given(companionOrderMapper.selectCount(any())).willReturn(1L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> elderService.delete(ELDER_ID));

        assertEquals(ResultCode.CONFLICT.getCode(), ex.getCode());
        verify(elderProfileMapper, never()).deleteById(any(Long.class));
    }

    @Test
    @DisplayName("删除：无进行中订单则逻辑删除并解除绑定")
    void deleteShouldLogicallyDeleteAndUnbind() {
        loginAs(RoleConstants.FAMILY, FAMILY_ID);
        given(elderProfileMapper.selectById(ELDER_ID)).willReturn(elderProfile());
        given(relationMapper.selectList(any())).willReturn(List.of(relation(ELDER_ID, BindStatus.BOUND)));
        given(companionOrderMapper.selectCount(any())).willReturn(0L);

        elderService.delete(ELDER_ID);

        verify(elderProfileMapper).deleteById(ELDER_ID);
        // 关系也要置为 UNBOUND，否则列表会捞出指向已删档案的关系行
        verify(relationMapper).update(any(), any());
    }

    @Test
    @DisplayName("解绑：绑定关系不存在返回 2005")
    void unbindShouldRejectWhenRelationNotFound() {
        loginAs(RoleConstants.FAMILY, FAMILY_ID);
        given(elderProfileMapper.selectById(ELDER_ID)).willReturn(elderProfile());
        given(relationMapper.selectList(any())).willReturn(List.of());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> elderService.unbind(ELDER_ID));

        assertEquals(ResultCode.RELATION_NOT_FOUND.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("解绑：仍是已解绑状态时同样返回 2005")
    void unbindShouldRejectWhenAlreadyUnbound() {
        loginAs(RoleConstants.FAMILY, FAMILY_ID);
        given(elderProfileMapper.selectById(ELDER_ID)).willReturn(elderProfile());
        given(relationMapper.selectList(any())).willReturn(List.of(relation(ELDER_ID, BindStatus.UNBOUND)));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> elderService.unbind(ELDER_ID));

        assertEquals(ResultCode.RELATION_NOT_FOUND.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("解绑：有进行中订单返回 409，关系不被改动")
    void unbindShouldRejectWhenActiveOrderExists() {
        loginAs(RoleConstants.FAMILY, FAMILY_ID);
        given(elderProfileMapper.selectById(ELDER_ID)).willReturn(elderProfile());
        given(relationMapper.selectList(any())).willReturn(List.of(relation(ELDER_ID, BindStatus.BOUND)));
        given(companionOrderMapper.selectCount(any())).willReturn(2L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> elderService.unbind(ELDER_ID));

        assertEquals(ResultCode.CONFLICT.getCode(), ex.getCode());
        verify(relationMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("解绑：成功后关系置 UNBOUND，且档案在无其他绑定人时转为 UNBOUND")
    void unbindShouldSucceedAndMarkElderUnbound() {
        loginAs(RoleConstants.FAMILY, FAMILY_ID);
        given(elderProfileMapper.selectById(ELDER_ID)).willReturn(elderProfile());
        // 第一次是找「我的关系行」，第二次是数「还有几个人绑着」
        given(relationMapper.selectList(any())).willReturn(List.of(relation(ELDER_ID, BindStatus.BOUND)));
        given(companionOrderMapper.selectCount(any())).willReturn(0L);
        given(relationMapper.selectCount(any())).willReturn(0L);

        elderService.unbind(ELDER_ID);

        verify(relationMapper).update(any(), any());
        verify(elderProfileMapper).update(any(), any());
    }

    /* ================================================================== */
    /* 绑定                                                                */
    /* ================================================================== */

    @Test
    @DisplayName("绑定：邀请码一期未开放，返回 501")
    void bindShouldRejectInviteCode() {
        loginAs(RoleConstants.FAMILY, FAMILY_ID);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> elderService.bind(new ElderBindDTO()
                        .setBindType("INVITE_CODE").setBindValue("ABC123").setRelation("SON")));

        assertEquals(ResultCode.NOT_IMPLEMENTED.getCode(), ex.getCode());
        // 未实现的功能不能悄悄写数据
        verify(sysUserMapper, never()).selectOne(any());
        verify(relationMapper, never()).insert(any(FamilyElderRelation.class));
    }

    @Test
    @DisplayName("绑定：手机号格式不合法返回 400")
    void bindShouldRejectInvalidPhone() {
        loginAs(RoleConstants.FAMILY, FAMILY_ID);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> elderService.bind(new ElderBindDTO()
                        .setBindType("PHONE").setBindValue("12345").setRelation("SON")));

        assertEquals(ResultCode.PARAM_ERROR.getCode(), ex.getCode());
        verify(sysUserMapper, never()).selectOne(any());
    }

    @Test
    @DisplayName("绑定：手机号没有对应的老人账号返回 2001")
    void bindShouldRejectUnknownPhone() {
        loginAs(RoleConstants.FAMILY, FAMILY_ID);
        given(sysUserMapper.selectOne(any())).willReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> elderService.bind(phoneBindDto()));

        assertEquals(ResultCode.ELDER_NOT_FOUND.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("绑定：老人账号被封禁统一返回 2001（防手机号枚举）")
    void bindShouldRejectDisabledElderAccount() {
        loginAs(RoleConstants.FAMILY, FAMILY_ID);
        SysUser elderUser = new SysUser();
        elderUser.setId(230L);
        elderUser.setRole(RoleConstants.ELDER);
        elderUser.setStatus("DISABLED");
        given(sysUserMapper.selectOne(any())).willReturn(elderUser);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> elderService.bind(phoneBindDto()));

        // 账号不存在 / 已封禁 / 已被他人绑定 / 未建档 对调用方统一为 2001，
        // 避免攻击者通过差异枚举手机号背后的老人账号状态
        assertEquals(ResultCode.ELDER_NOT_FOUND.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("绑定：该老人已被其他家属绑定统一返回 2001（防手机号枚举）")
    void bindShouldRejectWhenBoundByOtherFamily() {
        loginAs(RoleConstants.FAMILY, FAMILY_ID);
        given(sysUserMapper.selectOne(any())).willReturn(elderUser());
        given(elderProfileMapper.selectList(any())).willReturn(List.of(elderProfile()));
        given(relationMapper.selectList(any())).willReturn(List.of(relation(ELDER_ID, BindStatus.BOUND, OTHER_FAMILY_ID)));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> elderService.bind(phoneBindDto()));

        assertEquals(ResultCode.ELDER_NOT_FOUND.getCode(), ex.getCode());
        verify(relationMapper, never()).insert(any(FamilyElderRelation.class));
    }

    @Test
    @DisplayName("绑定：已绑到自己账号时返回 409，不重复建关系")
    void bindShouldRejectWhenAlreadyBoundToMe() {
        loginAs(RoleConstants.FAMILY, FAMILY_ID);
        given(sysUserMapper.selectOne(any())).willReturn(elderUser());
        given(elderProfileMapper.selectList(any())).willReturn(List.of(elderProfile()));
        // 第一次查「别人绑了吗」→ 空；第二次查「我绑了吗」→ 已绑定
        given(relationMapper.selectList(any()))
                .willReturn(List.of())
                .willReturn(List.of(relation(ELDER_ID, BindStatus.BOUND, FAMILY_ID)));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> elderService.bind(phoneBindDto()));

        assertEquals(ResultCode.CONFLICT.getCode(), ex.getCode());
        verify(relationMapper, never()).insert(any(FamilyElderRelation.class));
    }

    @Test
    @DisplayName("绑定：首次绑定成功，写入 BOUND 关系并同步档案状态")
    void bindShouldSucceedAndCreateBoundRelation() {
        loginAs(RoleConstants.FAMILY, FAMILY_ID);
        given(sysUserMapper.selectOne(any())).willReturn(elderUser());
        ElderProfile profile = elderProfile();
        profile.setUserId(201L);
        profile.setBindStatus(BindStatus.UNBOUND.name());
        given(elderProfileMapper.selectList(any())).willReturn(List.of(profile));
        given(relationMapper.selectList(any())).willReturn(List.of());

        Long elderId = elderService.bind(phoneBindDto());

        assertEquals(ELDER_ID, elderId);

        ArgumentCaptor<FamilyElderRelation> captor =
                ArgumentCaptor.forClass(FamilyElderRelation.class);
        verify(relationMapper).insert(captor.capture());
        FamilyElderRelation saved = captor.getValue();
        assertEquals(FAMILY_ID, saved.getFamilyId());
        assertEquals(ELDER_ID, saved.getElderId());
        assertEquals("SON", saved.getRelation());
        assertEquals("PHONE", saved.getBindType());
        assertEquals(BindStatus.BOUND.name(), saved.getStatus());
        assertEquals(1, saved.getIsDefault());

        // 档案侧也要标成已绑定
        verify(elderProfileMapper).update(any(), any());
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

    /** 档案：姓名/手机/地址/病史齐全，身份证为密文，user_id = 201 */
    private ElderProfile elderProfile() {
        ElderProfile elder = new ElderProfile();
        elder.setId(ELDER_ID);
        elder.setUserId(201L);
        elder.setName("张德海");
        elder.setGender("MALE");
        elder.setBirthDate(LocalDate.of(1948, 3, 12));
        elder.setIdCard(AesUtil.encrypt(PLAIN_ID_CARD, AES_KEY));
        elder.setPhone("13911112222");
        elder.setAddress(PLAIN_ADDRESS);
        elder.setEmergencyContact("张四");
        elder.setEmergencyPhone("13899998888");
        elder.setMedicalHistory("高血压、2型糖尿病，长期服药");
        elder.setAllergyHistory("青霉素过敏");
        elder.setMobilityLevel("ASSIST");
        elder.setFavoriteHospital("海南省人民医院");
        elder.setBindStatus(BindStatus.BOUND.name());
        elder.setCreateBy(FAMILY_ID);
        return elder;
    }

    private SysUser elderUser() {
        SysUser user = new SysUser();
        user.setId(201L);
        user.setUsername("elder001");
        user.setRole(RoleConstants.ELDER);
        user.setStatus("NORMAL");
        return user;
    }

    private FamilyElderRelation relation(Long elderId, BindStatus status) {
        return relation(elderId, status, FAMILY_ID);
    }

    private FamilyElderRelation relation(Long elderId, BindStatus status, Long familyId) {
        FamilyElderRelation rel = new FamilyElderRelation();
        rel.setId(9001L);
        rel.setFamilyId(familyId);
        rel.setElderId(elderId);
        rel.setRelation("SON");
        rel.setBindType("PHONE");
        rel.setIsDefault(1);
        rel.setStatus(status.name());
        return rel;
    }

    private ElderBindDTO phoneBindDto() {
        return new ElderBindDTO().setBindType("PHONE").setBindValue("13911112222").setRelation("SON");
    }

    private Page<ElderProfile> pageOf(ElderProfile... elders) {
        Page<ElderProfile> page = new Page<>(1, 10, elders.length);
        page.setRecords(List.of(elders));
        return page;
    }

    /**
     * 抓取传给 {@code update(null, wrapper)} 的更新包装器。
     *
     * <p>必须按 {@link LambdaUpdateWrapper} 类型抓：{@code getSqlSet()} 与
     * {@code getParamNameValuePairs()} 是 {@code Update} 接口上的方法，
     * 按父接口 {@code Wrapper} 抓会取不到。</p>
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private LambdaUpdateWrapper<ElderProfile> captureUpdateWrapper() {
        ArgumentCaptor<LambdaUpdateWrapper> captor = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(elderProfileMapper).update(any(), captor.capture());
        return captor.getValue();
    }
}

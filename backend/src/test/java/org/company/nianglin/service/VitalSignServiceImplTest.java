package org.company.nianglin.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.company.nianglin.common.PageResult;
import org.company.nianglin.common.ResultCode;
import org.company.nianglin.constant.OrderStatus;
import org.company.nianglin.constant.RoleConstants;
import org.company.nianglin.constant.VitalSource;
import org.company.nianglin.dto.VitalSignCreateDTO;
import org.company.nianglin.dto.VitalSignQuery;
import org.company.nianglin.dto.VitalTrendQuery;
import org.company.nianglin.entity.CompanionOrder;
import org.company.nianglin.entity.ElderProfile;
import org.company.nianglin.entity.VitalSignRecord;
import org.company.nianglin.exception.BusinessException;
import org.company.nianglin.mapper.CompanionOrderMapper;
import org.company.nianglin.mapper.SysUserMapper;
import org.company.nianglin.mapper.VitalSignRecordMapper;
import org.company.nianglin.security.LoginUser;
import org.company.nianglin.service.impl.VitalSignServiceImpl;
import org.company.nianglin.support.MybatisLambdaCache;
import org.company.nianglin.vo.VitalSignVO;
import org.company.nianglin.vo.VitalTrendVO;
import org.company.nianglin.vo.VitalWithdrawVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.function.Executable;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.times;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 家属端体征读写单测（V4 · P2-2）。
 *
 * <p>覆盖规格 §11.1 的 1–11、15、16：录入成功、唯一键冲突、类型互斥、
 * 数值与时间合法性、撤回权限、趋势升序与 500 点截断、趋势缺类型。</p>
 *
 * <h3>本类刻意不做的事</h3>
 *
 * <p><b>不验证医学含义</b>：上下限断言只证明「误录被拦」，不证明「数值好不好」——
 * 这正是合规红线的口径（输入合法性 ≠ 医学判定）。</p>
 *
 * @author 银龄伴诊团队
 * @since V4 · P2-2
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("体征：家属 / 陪诊员录入、去重、校验、撤回与趋势")
class VitalSignServiceImplTest {

    private static final Long ELDER_ID = 301L;
    private static final Long FAMILY_ID = 11L;
    private static final Long OTHER_FAMILY_ID = 12L;
    private static final Long COMPANION_ID = 21L;
    private static final Long ORDER_ID = 1001L;

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
        MybatisLambdaCache.warmUp();
        service = new VitalSignServiceImpl(vitalSignRecordMapper, companionOrderMapper,
                elderService, sysUserMapper);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    /* ================================================================== */
    /* 1. 录入成功                                                         */
    /* ================================================================== */

    @Test
    @DisplayName("家属录入血压：来源 FAMILY_MANUAL、无订单、valueText=128/78 mmHg、秒被截断")
    void createForFamilyShouldWriteFamilySource() {
        loginAs(FAMILY_ID, RoleConstants.FAMILY);
        given(elderService.requireAccessible(ELDER_ID)).willReturn(new ElderProfile());
        given(vitalSignRecordMapper.insert(any(VitalSignRecord.class))).willReturn(1);

        LocalDateTime measuredAt = LocalDateTime.now().minusMinutes(1).withSecond(42);
        VitalSignVO vo = service.createForFamily(ELDER_ID,
                bloodPressureDto(128, 78, measuredAt));

        ArgumentCaptor<VitalSignRecord> captor = ArgumentCaptor.forClass(VitalSignRecord.class);
        verify(vitalSignRecordMapper).insert(captor.capture());
        VitalSignRecord saved = captor.getValue();

        assertEquals(VitalSource.FAMILY_MANUAL.name(), saved.getSourceType());
        assertEquals(FAMILY_ID, saved.getOperatorUserId());
        assertNull(saved.getOrderId(), "家属代录不应带订单 ID");
        assertEquals(ELDER_ID, saved.getElderId());
        assertEquals(0, saved.getMeasuredAt().getSecond(), "测量时间必须截断到分钟，否则去重键失效");
        assertEquals("128/78 mmHg", vo.getValueText());
    }

    /* ================================================================== */
    /* 2–4. 去重与撤回后重录                                                */
    /* ================================================================== */

    @Test
    @DisplayName("同一分钟再录：唯一键冲突转 2008，第二次不入库")
    void duplicateShouldMapTo2008() {
        loginAs(FAMILY_ID, RoleConstants.FAMILY);
        given(elderService.requireAccessible(ELDER_ID)).willReturn(new ElderProfile());
        given(vitalSignRecordMapper.insert(any(VitalSignRecord.class)))
                .willThrow(new DuplicateKeyException("uk_vital_dedup"));

        assertCode(2008, () -> service.createForFamily(ELDER_ID,
                bloodPressureDto(128, 78, LocalDateTime.now().minusMinutes(1))));
    }

    @Test
    @DisplayName("同一分钟不同类型：两条都能录入（去重键含类型）")
    void differentTypeSameMinuteShouldPass() {
        loginAs(FAMILY_ID, RoleConstants.FAMILY);
        given(elderService.requireAccessible(ELDER_ID)).willReturn(new ElderProfile());
        given(vitalSignRecordMapper.insert(any(VitalSignRecord.class))).willReturn(1);

        LocalDateTime measuredAt = LocalDateTime.now().minusMinutes(1);
        service.createForFamily(ELDER_ID, bloodPressureDto(128, 78, measuredAt));
        service.createForFamily(ELDER_ID, weightDto(new BigDecimal("62.5"), measuredAt));

        ArgumentCaptor<VitalSignRecord> captor = ArgumentCaptor.forClass(VitalSignRecord.class);
        verify(vitalSignRecordMapper, times(2)).insert(captor.capture());
        assertEquals("WEIGHT", captor.getAllValues().get(1).getVitalType());
    }

    @Test
    @DisplayName("撤回后同分钟重录：逻辑删除释放唯一键槽位，重新录入成功")
    void reinsertAfterWithdrawShouldPass() {
        loginAs(FAMILY_ID, RoleConstants.FAMILY);
        given(elderService.requireAccessible(ELDER_ID)).willReturn(new ElderProfile());
        given(vitalSignRecordMapper.selectById(1L)).willReturn(
                record(1L, "BLOOD_PRESSURE", VitalSource.FAMILY_MANUAL, FAMILY_ID));
        given(vitalSignRecordMapper.insert(any(VitalSignRecord.class))).willReturn(1);

        service.withdrawByFamily(1L);
        service.createForFamily(ELDER_ID, bloodPressureDto(130, 80, LocalDateTime.now().minusMinutes(1)));

        verify(vitalSignRecordMapper).deleteById(1L);
        verify(vitalSignRecordMapper).insert(any(VitalSignRecord.class));
    }

    /* ================================================================== */
    /* 5–8. 输入合法性                                                     */
    /* ================================================================== */

    @Test
    @DisplayName("字段互斥：血压缺舒张压、血糖缺场景、体重带血压字段都返回 400")
    void mutuallyExclusiveFieldsShouldReturn400() {
        loginAs(FAMILY_ID, RoleConstants.FAMILY);
        given(elderService.requireAccessible(ELDER_ID)).willReturn(new ElderProfile());

        VitalSignCreateDTO missingDiastolic = bloodPressureDto(128, 0, LocalDateTime.now());
        missingDiastolic.setDiastolic(null);
        assertCode(400, () -> service.createForFamily(ELDER_ID, missingDiastolic));

        VitalSignCreateDTO glucoseWithoutContext = glucoseDto(new BigDecimal("6.2"), null);
        assertCode(400, () -> service.createForFamily(ELDER_ID, glucoseWithoutContext));

        VitalSignCreateDTO weightWithSystolic = weightDto(new BigDecimal("62.5"), LocalDateTime.now());
        weightWithSystolic.setSystolic(120);
        assertCode(400, () -> service.createForFamily(ELDER_ID, weightWithSystolic));

        verify(vitalSignRecordMapper, never()).insert(any(VitalSignRecord.class));
    }

    @Test
    @DisplayName("数值超界：收缩压 1280 / 血糖 0 / 体重 999 都返回 400")
    void outOfRangeValuesShouldReturn400() {
        loginAs(FAMILY_ID, RoleConstants.FAMILY);
        given(elderService.requireAccessible(ELDER_ID)).willReturn(new ElderProfile());

        assertCode(400, () -> service.createForFamily(ELDER_ID,
                bloodPressureDto(1280, 78, LocalDateTime.now())));
        assertCode(400, () -> service.createForFamily(ELDER_ID,
                glucoseDto(BigDecimal.ZERO, "FASTING")));
        assertCode(400, () -> service.createForFamily(ELDER_ID,
                weightDto(new BigDecimal("999"), LocalDateTime.now())));

        verify(vitalSignRecordMapper, never()).insert(any(VitalSignRecord.class));
    }

    @Test
    @DisplayName("舒张压不小于收缩压：返回 400")
    void diastolicNotLessThanSystolicShouldReturn400() {
        loginAs(FAMILY_ID, RoleConstants.FAMILY);
        given(elderService.requireAccessible(ELDER_ID)).willReturn(new ElderProfile());

        assertCode(400, () -> service.createForFamily(ELDER_ID,
                bloodPressureDto(80, 80, LocalDateTime.now())));
    }

    @Test
    @DisplayName("测量时间晚于当前 10 分钟：返回 400")
    void futureMeasuredAtShouldReturn400() {
        loginAs(FAMILY_ID, RoleConstants.FAMILY);
        given(elderService.requireAccessible(ELDER_ID)).willReturn(new ElderProfile());

        assertCode(400, () -> service.createForFamily(ELDER_ID,
                bloodPressureDto(128, 78, LocalDateTime.now().plusMinutes(10))));
    }

    /* ================================================================== */
    /* 9–11. 撤回权限                                                      */
    /* ================================================================== */

    @Test
    @DisplayName("撤回本人代录：逻辑删除并返回 deleted=true")
    void withdrawOwnRecordShouldSucceed() {
        loginAs(FAMILY_ID, RoleConstants.FAMILY);
        given(elderService.requireAccessible(ELDER_ID)).willReturn(new ElderProfile());
        given(vitalSignRecordMapper.selectById(9L)).willReturn(
                record(9L, "WEIGHT", VitalSource.FAMILY_MANUAL, FAMILY_ID));

        VitalWithdrawVO vo = service.withdrawByFamily(9L);

        assertEquals(9L, vo.getRecordId());
        assertTrue(vo.getDeleted());
        verify(vitalSignRecordMapper).deleteById(9L);
    }

    @Test
    @DisplayName("撤回别家老人记录：归属校验抛 2006，不执行删除")
    void withdrawOtherFamilyElderShouldReturn2006() {
        loginAs(FAMILY_ID, RoleConstants.FAMILY);
        given(vitalSignRecordMapper.selectById(9L)).willReturn(
                record(9L, "WEIGHT", VitalSource.FAMILY_MANUAL, OTHER_FAMILY_ID));
        given(elderService.requireAccessible(ELDER_ID))
                .willThrow(new BusinessException(ResultCode.NO_PERMISSION_FOR_ELDER));

        assertCode(2006, () -> service.withdrawByFamily(9L));
        verify(vitalSignRecordMapper, never()).deleteById(anyLong());
    }

    @Test
    @DisplayName("撤回陪诊员代录：返回 2010")
    void withdrawCompanionRecordShouldReturn2010() {
        loginAs(FAMILY_ID, RoleConstants.FAMILY);
        given(elderService.requireAccessible(ELDER_ID)).willReturn(new ElderProfile());
        given(vitalSignRecordMapper.selectById(9L)).willReturn(
                record(9L, "WEIGHT", VitalSource.COMPANION_SERVICE, 21L));

        assertCode(2010, () -> service.withdrawByFamily(9L));
        verify(vitalSignRecordMapper, never()).deleteById(anyLong());
    }

    @Test
    @DisplayName("撤回不存在的记录：返回 2009")
    void withdrawMissingRecordShouldReturn2009() {
        loginAs(FAMILY_ID, RoleConstants.FAMILY);
        given(vitalSignRecordMapper.selectById(9L)).willReturn(null);

        assertCode(2009, () -> service.withdrawByFamily(9L));
    }

    /* ================================================================== */
    /* 15–16. 时间线与趋势                                                 */
    /* ================================================================== */

    @Test
    @DisplayName("列表：按测量时间倒序返回，分页元信息与记录一致")
    void listShouldKeepDescendingOrder() {
        loginAs(FAMILY_ID, RoleConstants.FAMILY);
        given(elderService.requireAccessible(ELDER_ID)).willReturn(new ElderProfile());
        List<VitalSignRecord> rows = List.of(
                recordAt(2L, LocalDateTime.now().minusHours(1)),
                recordAt(1L, LocalDateTime.now().minusHours(2)));
        given(vitalSignRecordMapper.selectPage(any(), any())).willAnswer(invocation -> {
            Page<VitalSignRecord> requested = invocation.getArgument(0);
            requested.setRecords(rows);
            requested.setTotal(rows.size());
            return requested;
        });

        PageResult<VitalSignVO> result = service.listForFamily(ELDER_ID, new VitalSignQuery());

        assertEquals(2L, result.getTotal());
        assertEquals(1L, result.getPage());
        assertEquals(10L, result.getSize());
        assertEquals(2L, result.getRecords().get(0).getId());
        assertEquals(1L, result.getRecords().get(1).getId());
    }

    @Test
    @DisplayName("趋势：超过 500 点取最近 500 条、升序返回并置 truncated=true")
    void trendShouldTruncateToRecent500AndSortAscending() {
        loginAs(FAMILY_ID, RoleConstants.FAMILY);
        given(elderService.requireAccessible(ELDER_ID)).willReturn(new ElderProfile());

        LocalDateTime base = LocalDateTime.now().withSecond(0).withNano(0).minusMinutes(1);
        List<VitalSignRecord> desc = new ArrayList<>();
        for (int i = 0; i <= 500; i++) {
            desc.add(recordAt(1000L - i, base.minusMinutes(i)));
        }
        given(vitalSignRecordMapper.selectList(any())).willReturn(desc);

        VitalTrendVO trend = service.trendForFamily(ELDER_ID, trendQuery("BLOOD_PRESSURE"));

        assertEquals(500, trend.getPointCount());
        assertTrue(trend.getTruncated());
        assertEquals(base.minusMinutes(499), trend.getPoints().get(0).getMeasuredAt(),
                "截断后保留的是最近 500 条，升序首点应为第 500 近的一条");
        assertEquals(base, trend.getPoints().get(499).getMeasuredAt());
    }

    @Test
    @DisplayName("趋势缺类型：返回 400")
    void trendWithoutTypeShouldReturn400() {
        loginAs(FAMILY_ID, RoleConstants.FAMILY);
        given(elderService.requireAccessible(ELDER_ID)).willReturn(new ElderProfile());

        assertCode(400, () -> service.trendForFamily(ELDER_ID, trendQuery(" ")));
    }

    /* ================================================================== */
    /* 12–14. 陪诊员服务中代录                                              */
    /* ================================================================== */

    @Test
    @DisplayName("陪诊员代录：老人取订单的 elderId，来源 COMPANION_SERVICE 并带 orderId")
    void companionCreateShouldUseOrderElderAndOrderId() {
        loginAs(COMPANION_ID, RoleConstants.COMPANION);
        given(companionOrderMapper.selectById(ORDER_ID))
                .willReturn(order(ORDER_ID, ELDER_ID, COMPANION_ID, OrderStatus.IN_SERVICE));
        given(vitalSignRecordMapper.insert(any(VitalSignRecord.class))).willReturn(1);

        VitalSignVO vo = service.createForCompanion(ORDER_ID,
                weightDto(new BigDecimal("62.5"), LocalDateTime.now().minusMinutes(1)));

        ArgumentCaptor<VitalSignRecord> captor = ArgumentCaptor.forClass(VitalSignRecord.class);
        verify(vitalSignRecordMapper).insert(captor.capture());
        VitalSignRecord saved = captor.getValue();

        assertEquals(ELDER_ID, saved.getElderId());
        assertEquals(VitalSource.COMPANION_SERVICE.name(), saved.getSourceType());
        assertEquals(COMPANION_ID, saved.getOperatorUserId());
        assertEquals(ORDER_ID, saved.getOrderId());
        assertEquals("62.5 kg", vo.getValueText());
        verify(elderService, never()).requireAccessible(anyLong());
    }

    @Test
    @DisplayName("陪诊员代录：订单不存在 3001、非本单 3004、非服务中 3002")
    void companionPreconditionsShouldFailWithOrderCodes() {
        loginAs(COMPANION_ID, RoleConstants.COMPANION);

        assertCode(3001, () -> service.createForCompanion(ORDER_ID,
                weightDto(new BigDecimal("62.5"), LocalDateTime.now())));

        given(companionOrderMapper.selectById(ORDER_ID))
                .willReturn(order(ORDER_ID, ELDER_ID, 999L, OrderStatus.IN_SERVICE));
        assertCode(3004, () -> service.createForCompanion(ORDER_ID,
                weightDto(new BigDecimal("62.5"), LocalDateTime.now())));

        given(companionOrderMapper.selectById(ORDER_ID))
                .willReturn(order(ORDER_ID, ELDER_ID, COMPANION_ID, OrderStatus.COMPLETED));
        assertCode(3002, () -> service.createForCompanion(ORDER_ID,
                weightDto(new BigDecimal("62.5"), LocalDateTime.now())));

        verify(vitalSignRecordMapper, never()).insert(any(VitalSignRecord.class));
    }

    @Test
    @DisplayName("陪诊员撤回本人本单记录：成功；记录属于其它订单返回 3004")
    void companionWithdrawShouldRespectOrderOwnership() {
        loginAs(COMPANION_ID, RoleConstants.COMPANION);
        given(companionOrderMapper.selectById(ORDER_ID))
                .willReturn(order(ORDER_ID, ELDER_ID, COMPANION_ID, OrderStatus.IN_SERVICE));
        given(vitalSignRecordMapper.selectById(9L)).willReturn(
                companionRecord(9L, ORDER_ID));

        assertTrue(service.withdrawByCompanion(ORDER_ID, 9L).getDeleted());
        verify(vitalSignRecordMapper).deleteById(9L);

        given(vitalSignRecordMapper.selectById(10L)).willReturn(
                companionRecord(10L, 2002L));
        assertCode(3004, () -> service.withdrawByCompanion(ORDER_ID, 10L));
    }

    @Test
    @DisplayName("陪诊员撤回：订单已离开服务中返回 3002，不执行删除")
    void companionWithdrawAfterServiceShouldReturn3002() {
        loginAs(COMPANION_ID, RoleConstants.COMPANION);
        given(companionOrderMapper.selectById(ORDER_ID))
                .willReturn(order(ORDER_ID, ELDER_ID, COMPANION_ID, OrderStatus.COMPLETED));

        assertCode(3002, () -> service.withdrawByCompanion(ORDER_ID, 9L));
        verify(vitalSignRecordMapper, never()).deleteById(anyLong());
    }

    @Test
    @DisplayName("陪诊员按订单查询：服务中 / 已完成 / 已评价可读，已接单 3002、非本单 3004、不存在 3001")
    void companionListShouldRespectReadableStatuses() {
        loginAs(COMPANION_ID, RoleConstants.COMPANION);
        given(vitalSignRecordMapper.selectPage(any(), any())).willAnswer(invocation -> {
            Page<VitalSignRecord> page = invocation.getArgument(0);
            page.setRecords(List.of(companionRecord(9L, ORDER_ID)));
            page.setTotal(1);
            return page;
        });

        given(companionOrderMapper.selectById(ORDER_ID))
                .willReturn(order(ORDER_ID, ELDER_ID, COMPANION_ID, OrderStatus.IN_SERVICE));
        PageResult<VitalSignVO> page = service.listForCompanion(ORDER_ID, new VitalSignQuery());
        assertEquals(1L, page.getTotal());
        assertEquals(ORDER_ID, page.getRecords().get(0).getOrderId());
        verify(elderService, never()).requireAccessible(anyLong());

        given(companionOrderMapper.selectById(ORDER_ID))
                .willReturn(order(ORDER_ID, ELDER_ID, COMPANION_ID, OrderStatus.COMPLETED));
        assertEquals(1, service.listForCompanion(ORDER_ID, new VitalSignQuery()).getRecords().size());

        given(companionOrderMapper.selectById(ORDER_ID))
                .willReturn(order(ORDER_ID, ELDER_ID, COMPANION_ID, OrderStatus.REVIEWED));
        assertEquals(1, service.listForCompanion(ORDER_ID, new VitalSignQuery()).getRecords().size());

        given(companionOrderMapper.selectById(ORDER_ID))
                .willReturn(order(ORDER_ID, ELDER_ID, COMPANION_ID, OrderStatus.ACCEPTED));
        assertCode(3002, () -> service.listForCompanion(ORDER_ID, new VitalSignQuery()));

        given(companionOrderMapper.selectById(ORDER_ID))
                .willReturn(order(ORDER_ID, ELDER_ID, 999L, OrderStatus.COMPLETED));
        assertCode(3004, () -> service.listForCompanion(ORDER_ID, new VitalSignQuery()));

        given(companionOrderMapper.selectById(ORDER_ID)).willReturn(null);
        assertCode(3001, () -> service.listForCompanion(ORDER_ID, new VitalSignQuery()));
    }

    /* ================================================================== */
    /* 测试夹具                                                            */
    /* ================================================================== */

    private void loginAs(Long userId, String role) {
        LoginUser loginUser = new LoginUser(userId, "user-" + userId, role, 0, "jti",
                System.currentTimeMillis() + 60000);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, loginUser.authorities()));
    }

    private void assertCode(int expected, Executable executable) {
        BusinessException exception = assertThrows(BusinessException.class, executable);
        assertEquals(expected, exception.getCode());
        assertFalse(exception.getMessage() == null || exception.getMessage().isBlank());
    }

    private static VitalSignCreateDTO bloodPressureDto(int systolic, int diastolic, LocalDateTime measuredAt) {
        VitalSignCreateDTO dto = new VitalSignCreateDTO();
        dto.setVitalType("BLOOD_PRESSURE");
        dto.setMeasuredAt(measuredAt);
        dto.setSystolic(systolic);
        dto.setDiastolic(diastolic);
        return dto;
    }

    private static VitalSignCreateDTO glucoseDto(BigDecimal value, String context) {
        VitalSignCreateDTO dto = new VitalSignCreateDTO();
        dto.setVitalType("BLOOD_GLUCOSE");
        dto.setMeasuredAt(LocalDateTime.now().minusMinutes(1));
        dto.setGlucoseValue(value);
        dto.setGlucoseContext(context);
        return dto;
    }

    private static VitalSignCreateDTO weightDto(BigDecimal weight, LocalDateTime measuredAt) {
        VitalSignCreateDTO dto = new VitalSignCreateDTO();
        dto.setVitalType("WEIGHT");
        dto.setMeasuredAt(measuredAt);
        dto.setWeightKg(weight);
        return dto;
    }

    private static VitalTrendQuery trendQuery(String vitalType) {
        VitalTrendQuery query = new VitalTrendQuery();
        query.setVitalType(vitalType);
        return query;
    }

    private static VitalSignRecord record(Long id, String type, VitalSource source, Long operatorId) {
        VitalSignRecord record = recordAt(id, LocalDateTime.now().minusMinutes(1));
        record.setVitalType(type);
        record.setSourceType(source.name());
        record.setOperatorUserId(operatorId);
        record.setWeightKg(new BigDecimal("62.5"));
        return record;
    }

    private static VitalSignRecord recordAt(Long id, LocalDateTime measuredAt) {
        VitalSignRecord record = new VitalSignRecord();
        record.setId(id);
        record.setElderId(ELDER_ID);
        record.setVitalType("BLOOD_PRESSURE");
        record.setMeasuredAt(measuredAt);
        record.setSystolic(126);
        record.setDiastolic(76);
        record.setSourceType(VitalSource.FAMILY_MANUAL.name());
        record.setOperatorUserId(FAMILY_ID);
        return record;
    }

    private static CompanionOrder order(Long id, Long elderId, Long companionId, OrderStatus status) {
        CompanionOrder order = new CompanionOrder();
        order.setId(id);
        order.setElderId(elderId);
        order.setCompanionId(companionId);
        order.setStatus(status.name());
        return order;
    }

    private static VitalSignRecord companionRecord(Long id, Long orderId) {
        VitalSignRecord record = recordAt(id, LocalDateTime.now().minusMinutes(1));
        record.setVitalType("WEIGHT");
        record.setSystolic(null);
        record.setDiastolic(null);
        record.setWeightKg(new BigDecimal("62.5"));
        record.setSourceType(VitalSource.COMPANION_SERVICE.name());
        record.setOperatorUserId(COMPANION_ID);
        record.setOrderId(orderId);
        return record;
    }
}

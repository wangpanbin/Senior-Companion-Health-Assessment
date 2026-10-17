package org.company.nianglin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.company.nianglin.common.PageResult;
import org.company.nianglin.common.ResultCode;
import org.company.nianglin.config.FulfillmentProperties;
import org.company.nianglin.constant.AuditStatus;
import org.company.nianglin.constant.OrderStatus;
import org.company.nianglin.constant.PaymentStatus;
import org.company.nianglin.constant.RoleConstants;
import org.company.nianglin.dto.OrderHallQuery;
import org.company.nianglin.entity.CompanionOrder;
import org.company.nianglin.entity.CompanionProfile;
import org.company.nianglin.entity.ElderProfile;
import org.company.nianglin.exception.BusinessException;
import org.company.nianglin.mapper.CompanionOrderMapper;
import org.company.nianglin.mapper.CompanionProfileMapper;
import org.company.nianglin.mapper.ElderProfileMapper;
import org.company.nianglin.mapper.OrderReadMapper;
import org.company.nianglin.mapper.OrderRejectLogMapper;
import org.company.nianglin.mapper.OrderStatusLogMapper;
import org.company.nianglin.mapper.SysUserMapper;
import org.company.nianglin.security.LoginUser;
import org.company.nianglin.service.impl.OrderServiceImpl;
import org.company.nianglin.support.MybatisLambdaCache;
import org.company.nianglin.vo.OrderVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 订单大厅推荐序单测（V3.1 · T8）。
 *
 * <p>本类只验三件「单测能验、端到端看不见」的事：</p>
 * <ol>
 *   <li><b>排序键的白名单</b>：非法值（含 V3.4 才开放的距离 / 时间匹配 / 收益优先）
 *       必须 {@code 400} 且消息说明后续开放，<b>不静默回落推荐序</b>；</li>
 *   <li><b>缺省排序键来自配置</b>：请求不传时读 {@code hall-default-sort}，
 *       把配置改成未开放维度同样 {@code 400} —— 证明配置值没有被硬编码旁路；</li>
 *   <li><b>排序键的次序</b>：就诊时间 → 区域匹配 → ID，ID 是分页不重不漏的保证。</li>
 * </ol>
 *
 * <p>区域匹配只能验到「生成的 SQL 片段」这一层：包含式匹配靠
 * {@code LOCATE(档案文本, 医院名/地址) > 0} 表达，因此断言既钉住四个匹配条件，
 * 也钉住「不用 LIKE 通配符」—— 档案里的 {@code %} 必须被当普通字符，
 * 否则就是 spec 明令禁止的「推断」。</p>
 *
 * @author 银龄伴诊团队
 * @since V3.1
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("订单大厅推荐序：白名单拒绝 / 配置缺省 / 三级稳定排序")
class HallSortTest {

    private static final Long FAMILY_ID = 101L;
    private static final Long ELDER_ID = 401L;
    private static final Long COMPANION_ID = 301L;
    private static final Long ORDER_ID = 2001L;

    @Mock
    private CompanionOrderMapper orderMapper;

    @Mock
    private OrderStatusLogMapper statusLogMapper;

    @Mock
    private OrderRejectLogMapper rejectLogMapper;

    @Mock
    private ElderProfileMapper elderProfileMapper;

    @Mock
    private CompanionProfileMapper companionProfileMapper;

    @Mock
    private SysUserMapper sysUserMapper;

    @Mock
    private OrderReadMapper orderReadMapper;

    @Mock
    private ElderService elderService;

    @Mock
    private MessageService messageService;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private OrderFeeItemService orderFeeItemService;

    @Mock
    private FulfillmentService fulfillmentService;

    /** 用真实实例而非 mock：白名单要能同时管住「配置写错」这种情况 */
    private FulfillmentProperties fulfillmentProperties;

    private OrderServiceImpl orderService;

    @BeforeEach
    void setUp() {
        // 纯 Mockito 单测不起 Spring 容器，lambda 列名缓存是空的，wrapper 会抛
        // "can not find lambda cache"。详见 MybatisLambdaCache。
        MybatisLambdaCache.warmUp();

        fulfillmentProperties = new FulfillmentProperties();
        // 大厅链路不碰状态流转入口（ADR-0010），第 12 个入参传 null
        orderService = new OrderServiceImpl(orderMapper, statusLogMapper, rejectLogMapper,
                elderProfileMapper, companionProfileMapper, sysUserMapper, orderReadMapper,
                elderService, messageService, redisTemplate, new ObjectMapper(),
                null, orderFeeItemService, fulfillmentService, fulfillmentProperties);

        loginAsCompanion();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    /* ================================================================== */
    /* 1. 白名单：不静默降级                                              */
    /* ================================================================== */

    @ParameterizedTest(name = "sortBy={0}")
    @ValueSource(strings = {"DISTANCE", "TIME_MATCH", "INCOME", "FEE", "recommend_v2"})
    @DisplayName("未开放的排序维度一律 400，且消息说明该维度后续开放")
    void unopenedSortByShouldBeRejected(String sortBy) {
        stubCompanionProfile(AuditStatus.APPROVED, "美兰区", "周一至周五 08:00-18:00");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.hall(hallQuery(sortBy)));

        assertEquals(ResultCode.PARAM_ERROR.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("后续版本开放"),
                "错误消息要说明该维度后续开放，实际：" + ex.getMessage());
        assertTrue(ex.getMessage().contains(sortBy),
                "错误消息要回显传进来的取值，实际：" + ex.getMessage());
        verify(orderMapper, never()).selectPage(any(), any());
    }

    @Test
    @DisplayName("配置项写成未开放维度时同样 400 —— 白名单不只管请求参数")
    void illegalConfiguredDefaultShouldBeRejected() {
        fulfillmentProperties.setHallDefaultSort("INCOME");
        stubCompanionProfile(AuditStatus.APPROVED, "美兰区", "周一至周五");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.hall(new OrderHallQuery()));

        assertEquals(ResultCode.PARAM_ERROR.getCode(), ex.getCode());
        verify(orderMapper, never()).selectPage(any(), any());
    }

    @Test
    @DisplayName("资质未通过时先报 2003，即便排序维度也不合法")
    void unapprovedCompanionShouldWinOverIllegalSortBy() {
        stubCompanionProfile(AuditStatus.PENDING, "美兰区", "周一至周五");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.hall(hallQuery("DISTANCE")));

        assertEquals(ResultCode.COMPANION_NOT_AUDITED.getCode(), ex.getCode());
        verify(orderMapper, never()).selectPage(any(), any());
    }

    /* ================================================================== */
    /* 2. 缺省排序键来自配置                                               */
    /* ================================================================== */

    @Test
    @DisplayName("不传 sortBy 时用配置里的 RECOMMEND，产出的排序与显式传值逐字一致")
    void absentSortByShouldFallBackToConfiguredDefault() {
        stubCompanionProfile(AuditStatus.APPROVED, "美兰区", "周一至周五");
        stubHallQuery();

        orderService.hall(new OrderHallQuery());
        orderService.hall(hallQuery("RECOMMEND"));

        List<String> sqls = hallSqls(2);
        assertEquals(sqls.get(1), sqls.get(0),
                "缺省路径必须与显式传 RECOMMEND 走同一套排序");
        assertTrue(sqls.get(0).contains("visit_time ASC"), sqls.get(0));
    }

    @Test
    @DisplayName("排序键做去空格与大小写归一：' recommend ' 与 RECOMMEND 等价")
    void sortByShouldBeNormalized() {
        stubCompanionProfile(AuditStatus.APPROVED, "美兰区", "周一至周五");
        stubHallQuery();

        PageResult<OrderVO> result = orderService.hall(hallQuery("  recommend "));

        assertEquals(1, result.getRecords().size());
        assertTrue(hallSql().contains("ORDER BY"), hallSql());
    }

    /* ================================================================== */
    /* 3. 三级排序键：就诊时间 → 区域匹配 → ID                            */
    /* ================================================================== */

    @Test
    @DisplayName("推荐序是三级键：visit_time asc → 区域匹配者优先 → id asc")
    void recommendShouldSortByVisitTimeThenAreaMatchThenId() {
        stubCompanionProfile(AuditStatus.APPROVED, "美兰区", "周一至周五");
        stubHallQuery();

        orderService.hall(new OrderHallQuery());

        String sql = hallSql();
        int visitTime = sql.indexOf("visit_time ASC");
        int match = sql.indexOf("CASE WHEN");
        int id = sql.lastIndexOf("id ASC");
        assertTrue(visitTime >= 0, "第一排序键必须是就诊时间升序：" + sql);
        assertTrue(match > visitTime, "区域匹配是第二排序键：" + sql);
        assertTrue(id > match, "id 升序是兜底键，必须排在区域匹配之后：" + sql);
    }

    @Test
    @DisplayName("区域匹配覆盖医院名与地址 × 服务区域与可服务时段共四个条件")
    void areaMatchShouldCoverHospitalAndAddressForAreaAndTime() {
        stubCompanionProfile(AuditStatus.APPROVED, "美兰区", "周一至周五");
        stubHallQuery();

        orderService.hall(new OrderHallQuery());

        String sql = hallSql();
        assertTrue(sql.contains("LOCATE('美兰区', hospital) > 0"), sql);
        assertTrue(sql.contains("LOCATE('美兰区', address) > 0"), sql);
        assertTrue(sql.contains("LOCATE('周一至周五', hospital) > 0"), sql);
        assertTrue(sql.contains("LOCATE('周一至周五', address) > 0"), sql);
        assertTrue(sql.contains("THEN 0 ELSE 1 END"), "命中排在前、未命中排在后：" + sql);
    }

    @Test
    @DisplayName("档案自由文本不做推断：% 不是通配符，单引号被转义")
    void freeTextShouldNeverBeTreatedAsWildcardOrSql() {
        stubCompanionProfile(AuditStatus.APPROVED, "美兰%区' OR '1'='1", null);
        stubHallQuery();

        orderService.hall(new OrderHallQuery());

        String sql = hallSql();
        assertTrue(sql.contains("LOCATE('美兰%区'' OR ''1''=''1', hospital) > 0"),
                "单引号必须翻倍、% 必须当普通字符：" + sql);
        assertFalse(sql.contains("LIKE"), "包含式匹配不该用 LIKE 通配符：" + sql);
    }

    @Test
    @DisplayName("服务区域与可服务时段都为空时不产出匹配排序键（空不等于匹配一切）")
    void blankAreaShouldNotTreatEveryOrderAsMatched() {
        stubCompanionProfile(AuditStatus.APPROVED, "   ", null);
        stubHallQuery();

        orderService.hall(new OrderHallQuery());

        String sql = hallSql();
        assertFalse(sql.contains("CASE WHEN"),
                "两栏皆空时不应推断出任何匹配，排序键退化为就诊时间 + ID：" + sql);
        assertTrue(sql.contains("visit_time ASC") && sql.contains("id ASC"), sql);
    }

    /* ================================================================== */
    /* 4. 既有响应结构零变更                                               */
    /* ================================================================== */

    @Test
    @DisplayName("排序改动不动响应结构：地址、医院、脱敏姓名、状态文案照旧")
    void responseShapeShouldNotChange() {
        stubCompanionProfile(AuditStatus.APPROVED, "美兰区", "周一至周五");
        stubHallQuery();

        OrderVO vo = orderService.hall(new OrderHallQuery()).getRecords().get(0);

        assertEquals("海南省海口市秀英区秀华路19号 门诊大楼3楼", vo.getAddress());
        assertEquals("海南省人民医院", vo.getHospital());
        assertEquals("心血管内科", vo.getDepartment());
        assertEquals("张*海", vo.getElderName());
        assertEquals(OrderStatus.PENDING.name(), vo.getStatus());
        assertEquals(OrderStatus.PENDING.getLabel(), vo.getStatusLabel());
    }

    /* ================================================================== */
    /* 测试夹具                                                            */
    /* ================================================================== */

    private void loginAsCompanion() {
        LoginUser loginUser = new LoginUser(COMPANION_ID, "tester" + COMPANION_ID,
                RoleConstants.COMPANION, 0, "jti-hall-sort", System.currentTimeMillis() + 60000);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, loginUser.authorities()));
    }

    /** 只 stub 资质快照：排序被拒的用例走不到查询，别给多余桩（Mockito 严格模式会红） */
    private void stubCompanionProfile(AuditStatus auditStatus, String serviceArea, String availableTime) {
        CompanionProfile profile = new CompanionProfile();
        profile.setId(601L);
        profile.setUserId(COMPANION_ID);
        profile.setRealName("李建军");
        profile.setAuditStatus(auditStatus.name());
        profile.setServiceArea(serviceArea);
        profile.setAvailableTime(availableTime);
        given(companionProfileMapper.selectList(any())).willReturn(List.of(profile));
    }

    /** 大厅查询链路上的桩：拒过单列表 + 分页结果 + 老人档案 */
    private void stubHallQuery() {
        given(rejectLogMapper.selectList(any())).willReturn(List.of());
        given(orderMapper.selectPage(any(), any())).willReturn(pageOf(pendingOrder()));
        given(orderReadMapper.selectEldersIgnoringLogicDelete(any())).willReturn(List.of(elderProfile()));
    }

    private OrderHallQuery hallQuery(String sortBy) {
        OrderHallQuery query = new OrderHallQuery();
        query.setSortBy(sortBy);
        return query;
    }

    private String hallSql() {
        return hallSqls(1).get(0);
    }

    /**
     * 抓大厅查询的 SQL 片段（含 {@code last} 里的 ORDER BY）。
     *
     * <p>{@code selectPage} 是泛型方法，{@code ArgumentCaptor} 推不出类型，
     * 只能按 {@code LambdaQueryWrapper} 抓 —— 与 OrderServiceTest 同源。</p>
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private List<String> hallSqls(int times) {
        ArgumentCaptor<LambdaQueryWrapper> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(orderMapper, times(times)).selectPage(any(), captor.capture());
        return captor.getAllValues().stream().map(wrapper -> wrapper.getSqlSegment()).toList();
    }

    @SafeVarargs
    private final Page<CompanionOrder> pageOf(CompanionOrder... orders) {
        Page<CompanionOrder> page = new Page<>(1, 10, orders.length);
        page.setRecords(List.of(orders));
        return page;
    }

    private CompanionOrder pendingOrder() {
        CompanionOrder order = new CompanionOrder();
        order.setId(ORDER_ID);
        order.setOrderNo("NL20260915000001");
        order.setFamilyId(FAMILY_ID);
        order.setElderId(ELDER_ID);
        order.setHospital("海南省人民医院");
        order.setDepartment("心血管内科");
        order.setVisitTime(LocalDateTime.now().plusDays(3));
        order.setAddress("海南省海口市秀英区秀华路19号 门诊大楼3楼");
        order.setRemark("老人听力不好，请大声沟通");
        order.setStatus(OrderStatus.PENDING.name());
        order.setFee(new BigDecimal("128.00"));
        order.setPaymentStatus(PaymentStatus.UNPAID.name());
        order.setVersion(0);
        order.setArbitrateFlag(0);
        return order;
    }

    private ElderProfile elderProfile() {
        ElderProfile elder = new ElderProfile();
        elder.setId(ELDER_ID);
        elder.setUserId(201L);
        elder.setName("张德海");
        elder.setGender("MALE");
        elder.setBirthDate(LocalDate.of(1948, 3, 12));
        return elder;
    }
}

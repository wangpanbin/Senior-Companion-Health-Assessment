package org.company.nianglin.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.company.nianglin.common.PageResult;
import org.company.nianglin.common.ResultCode;
import org.company.nianglin.config.FulfillmentProperties;
import org.company.nianglin.config.MatchingProperties;
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
import org.company.nianglin.service.support.matching.OrderMatchRank;
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
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 订单大厅排序的单测（V3.4 · T8 · 承 V3.1 的 T8）。
 *
 * <h3>V3.1 → V3.4 变了什么</h3>
 *
 * <p>V3.1 的推荐序是<b>写进 SQL 的三级键</b>（就诊时间 → 区域匹配 CASE WHEN → ID），
 * 区域匹配靠 {@code LOCATE(档案文本, 医院名/地址)} 表达。
 * V3.4 换成<b>Java 侧五维加权</b>（候选集 → 打分 → 排序 → 内存分页），
 * 因此：</p>
 *
 * <ul>
 *   <li>SQL 里不再有「区域匹配排序键」，档案自由文本也不再拼进 SQL；</li>
 *   <li>排序键白名单从 {@code RECOMMEND} 一种扩到三种：
 *       {@code RECOMMEND} / {@code DISTANCE} / {@code TIME_MATCH}；
 *       {@code INCOME} <b>仍不开放</b>（没有真实结算金额口径）；</li>
 *   <li>响应结构与语义零变更：{@code total} 仍是「满足大厅筛选条件的待接单订单数」。</li>
 * </ul>
 *
 * <h3>为什么不再断言 SQL 片段</h3>
 *
 * <p>排序键一旦搬到 Java 侧，SQL 里就只剩「确定性截断键」
 * （就诊时间 + ID，保证超限时留下哪几条是确定的），匹配排序<b>不在 SQL 里</b>。
 * 所以本类断言的是「Java 侧排序的结果」，
 * 以及「SQL 里不再出现区域匹配排序键、档案文本不再拼进 SQL」。</p>
 *
 * @author 银龄伴诊团队
 * @since V3.1 · V3.4 改写
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("订单大厅排序：白名单 / 配置缺省 / Java 侧五维排序")
class HallSortTest {

    private static final Long FAMILY_ID = 101L;
    private static final Long ELDER_ID = 401L;
    private static final Long COMPANION_ID = 301L;
    private static final Long ORDER_ID = 2001L;
    private static final Long OTHER_ORDER_ID = 2002L;

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

    /** 智能调度：大厅排序的打分来源（V3.4） */
    @Mock
    private MatchingService matchingService;

    /** 用真实实例而非 mock：白名单要能同时管住「配置写错」这种情况 */
    private FulfillmentProperties fulfillmentProperties;

    /** 智能调度配置（V3.4）：大厅候选集上限等 */
    private MatchingProperties matchingProperties;

    private OrderServiceImpl orderService;

    @BeforeEach
    void setUp() {
        // 纯 Mockito 单测不起 Spring 容器，lambda 列名缓存是空的，wrapper 会抛
        // "can not find lambda cache"。详见 MybatisLambdaCache。
        MybatisLambdaCache.warmUp();

        fulfillmentProperties = new FulfillmentProperties();
        matchingProperties = new MatchingProperties();
        // 大厅链路不碰状态流转入口（ADR-0010），第 12 个入参传 null
        orderService = new OrderServiceImpl(orderMapper, statusLogMapper, rejectLogMapper,
                elderProfileMapper, companionProfileMapper, sysUserMapper, orderReadMapper,
                elderService, messageService, redisTemplate, new ObjectMapper(),
                null, orderFeeItemService, fulfillmentService, fulfillmentProperties,
                matchingService, matchingProperties);

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
    @ValueSource(strings = {"FEE", "recommend_v2", "INCOME_ORDER"})
    @DisplayName("未开放的排序维度一律 400，且消息说明该维度后续开放")
    void unopenedSortByShouldBeRejected(String sortBy) {
        stubCompanionProfile(AuditStatus.APPROVED);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.hall(hallQuery(sortBy)));

        assertEquals(ResultCode.PARAM_ERROR.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("后续版本开放"),
                "错误消息要说明该维度后续开放，实际：" + ex.getMessage());
        assertTrue(ex.getMessage().contains(sortBy.trim()),
                "错误消息要回显传进来的取值，实际：" + ex.getMessage());
        verify(orderMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("INCOME 仍 400，且消息说明缺的是「真实结算金额口径」")
    void incomeShouldStillBeRejected() {
        stubCompanionProfile(AuditStatus.APPROVED);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.hall(hallQuery("INCOME")));

        assertEquals(ResultCode.PARAM_ERROR.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("INCOME"), "消息要回显取值：" + ex.getMessage());
        assertTrue(ex.getMessage().contains("真实结算金额口径"),
                "消息要说明收益优先缺的是金额口径：" + ex.getMessage());
        verify(orderMapper, never()).selectList(any());
    }

    @ParameterizedTest(name = "sortBy={0}")
    @ValueSource(strings = {"DISTANCE", "TIME_MATCH", "recommend"})
    @DisplayName("V3.4 开放的排序维度不再 400（DISTANCE / TIME_MATCH / RECOMMEND）")
    void openedSortByShouldBeAccepted(String sortBy) {
        stubCompanionProfile(AuditStatus.APPROVED);
        stubHallQuery();

        PageResult<OrderVO> result = orderService.hall(hallQuery(sortBy));

        assertEquals(2, result.getTotal(), "开放后的排序维度必须能正常返回大厅");
        assertEquals(2, result.getRecords().size());
    }

    @Test
    @DisplayName("配置项写成未开放维度时同样 400 —— 白名单不只管请求参数")
    void illegalConfiguredDefaultShouldBeRejected() {
        fulfillmentProperties.setHallDefaultSort("FEE");
        stubCompanionProfile(AuditStatus.APPROVED);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.hall(new OrderHallQuery()));

        assertEquals(ResultCode.PARAM_ERROR.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("FEE"),
                "错误消息要回显配置里的取值：" + ex.getMessage());
        verify(orderMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("资质未通过时先报 2003，即便排序维度也不合法")
    void unapprovedCompanionShouldWinOverIllegalSortBy() {
        stubCompanionProfile(AuditStatus.PENDING);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.hall(hallQuery("FEE")));

        assertEquals(ResultCode.COMPANION_NOT_AUDITED.getCode(), ex.getCode());
        verify(orderMapper, never()).selectList(any());
    }

    /* ================================================================== */
    /* 2. 缺省排序键来自配置                                               */
    /* ================================================================== */

    @Test
    @DisplayName("不传 sortBy 时用配置里的缺省值，产出的排序与显式传值逐条一致")
    void absentSortByShouldFallBackToConfiguredDefault() {
        stubCompanionProfile(AuditStatus.APPROVED);
        stubHallQuery();

        PageResult<OrderVO> byDefault = orderService.hall(new OrderHallQuery());
        PageResult<OrderVO> byExplicit = orderService.hall(hallQuery("RECOMMEND"));

        assertEquals(orderIds(byDefault), orderIds(byExplicit),
                "缺省路径必须与显式传 RECOMMEND 走同一套排序");
    }

    @Test
    @DisplayName("配置里的缺省值改成 DISTANCE 时，不传 sortBy 就按距离维度排（证明缺省真来自配置）")
    void configuredDefaultShouldDriveTheSortDimension() {
        fulfillmentProperties.setHallDefaultSort("DISTANCE");
        stubCompanionProfile(AuditStatus.APPROVED);
        stubHallQuery();

        PageResult<OrderVO> byDefault = orderService.hall(new OrderHallQuery());

        // 桩里 2001 的距离分更高 → 按距离维度排时 2001 在前
        assertEquals(List.of(ORDER_ID, OTHER_ORDER_ID), orderIds(byDefault),
                "缺省排序键来自配置，而不是写死的 RECOMMEND");
    }

    /* ================================================================== */
    /* 3. 排序键归一                                                      */
    /* ================================================================== */

    @Test
    @DisplayName("排序键做去空格与大小写归一：' recommend ' 与 RECOMMEND 等价")
    void sortByShouldBeNormalized() {
        stubCompanionProfile(AuditStatus.APPROVED);
        stubHallQuery();

        PageResult<OrderVO> result = orderService.hall(hallQuery("  recommend "));

        assertEquals(2, result.getRecords().size());
        assertEquals(List.of(OTHER_ORDER_ID, ORDER_ID), orderIds(result),
                "归一后等价于 RECOMMEND（按总分降序）");
    }

    /* ================================================================== */
    /* 4. 排序发生在 Java 侧                                               */
    /* ================================================================== */

    @Test
    @DisplayName("RECOMMEND 按加权总分降序排（总分来自 Java 侧打分）")
    void recommendShouldSortByWeightedTotal() {
        stubCompanionProfile(AuditStatus.APPROVED);
        stubHallQuery();

        PageResult<OrderVO> result = orderService.hall(hallQuery("RECOMMEND"));

        assertEquals(List.of(OTHER_ORDER_ID, ORDER_ID), orderIds(result),
                "总分高的排前面");
        verify(matchingService, atLeastOnce()).scoreOrders(any(), any());
    }

    @Test
    @DisplayName("DISTANCE 按距离维度分排，与总分排序不是一回事")
    void distanceShouldSortByDistanceDimension() {
        stubCompanionProfile(AuditStatus.APPROVED);
        stubHallQuery();

        PageResult<OrderVO> result = orderService.hall(hallQuery("DISTANCE"));

        assertEquals(List.of(ORDER_ID, OTHER_ORDER_ID), orderIds(result),
                "距离维度分高的 2001 排前面（与总分排序的顺序正好相反）");
    }

    @Test
    @DisplayName("TIME_MATCH 按时间维度分排")
    void timeMatchShouldSortByTimeDimension() {
        stubCompanionProfile(AuditStatus.APPROVED);
        stubHallQuery();

        PageResult<OrderVO> result = orderService.hall(hallQuery("TIME_MATCH"));

        assertEquals(List.of(OTHER_ORDER_ID, ORDER_ID), orderIds(result),
                "时间维度分高的 2002 排前面");
    }

    @Test
    @DisplayName("SQL 里不再有区域匹配排序键，档案自由文本也不再拼进 SQL")
    void sqlShouldNotCarryAreaMatchRank() {
        stubCompanionProfile(AuditStatus.APPROVED);
        stubHallQuery();

        orderService.hall(new OrderHallQuery());

        String sql = hallSql();
        assertFalse(sql.contains("CASE WHEN"),
                "V3.1 的区域匹配排序键已废除，排序搬到 Java 侧：" + sql);
        assertFalse(sql.contains("LOCATE("),
                "档案自由文本不再拼进 SQL（那是 V3.1 的做法）：" + sql);
        assertTrue(sql.contains("visit_time ASC"),
                "保留的就诊时间升序只是确定性截断键：" + sql);
    }

    /* ================================================================== */
    /* 5. 既有响应结构零变更                                               */
    /* ================================================================== */

    @Test
    @DisplayName("排序改动不动响应结构：地址、医院、脱敏姓名、状态文案照旧")
    void responseShapeShouldNotChange() {
        stubCompanionProfile(AuditStatus.APPROVED);
        stubHallQuery();

        OrderVO vo = orderService.hall(new OrderHallQuery()).getRecords().get(0);

        assertEquals("海南省海口市秀英区秀华路19号 门诊大楼3楼", vo.getAddress());
        assertEquals("海南省人民医院", vo.getHospital());
        assertEquals("心血管内科", vo.getDepartment());
        assertEquals("张*海", vo.getElderName());
        assertEquals(OrderStatus.PENDING.name(), vo.getStatus());
        assertEquals(OrderStatus.PENDING.getLabel(), vo.getStatusLabel());
    }

    @Test
    @DisplayName("响应分页语义不变：total 是候选订单数，pages = ceil(total / size)")
    void pageMetaShouldStayUnchanged() {
        stubCompanionProfile(AuditStatus.APPROVED);
        stubHallQuery();

        PageResult<OrderVO> result = orderService.hall(new OrderHallQuery());

        assertEquals(2L, result.getTotal(), "total 仍是满足筛选条件的待接单订单数");
        assertEquals(1L, result.getPage());
        assertEquals(1L, result.getPages());
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
    private void stubCompanionProfile(AuditStatus auditStatus) {
        CompanionProfile profile = new CompanionProfile();
        profile.setId(601L);
        profile.setUserId(COMPANION_ID);
        profile.setRealName("李建军");
        profile.setAuditStatus(auditStatus.name());
        profile.setServiceArea("美兰区");
        profile.setAvailableTime("周一至周五");
        given(companionProfileMapper.selectList(any())).willReturn(List.of(profile));
    }

    /** 大厅查询链路上的桩：拒过单列表 + 候选订单 + 老人档案 + Java 侧打分结果 */
    private void stubHallQuery() {
        given(rejectLogMapper.selectList(any())).willReturn(List.of());
        // 用可变列表：大厅的排序是 Java 侧对候选集原地 sort（MyBatis-Plus 返回的也是可变列表）
        given(orderMapper.selectList(any()))
                .willReturn(new ArrayList<>(List.of(pendingOrder(), pendingOrder(OTHER_ORDER_ID))));
        given(orderReadMapper.selectEldersIgnoringLogicDelete(any())).willReturn(List.of(elderProfile()));
        // 五维打分的桩：总分与各维度分故意不同，用来区分「按总分排」和「按某一维排」
        given(matchingService.scoreOrders(any(), any())).willReturn(List.of(
                new OrderMatchRank(ORDER_ID, 0.30D, Map.of("distance", 0.95D, "time", 0.10D)),
                new OrderMatchRank(OTHER_ORDER_ID, 0.90D, Map.of("distance", 0.10D, "time", 0.95D))));
    }

    private OrderHallQuery hallQuery(String sortBy) {
        OrderHallQuery query = new OrderHallQuery();
        query.setSortBy(sortBy);
        return query;
    }

    /** 大厅返回的订单 ID 序列（用于断言顺序） */
    private List<Long> orderIds(PageResult<OrderVO> result) {
        return result.getRecords().stream().map(OrderVO::getId).toList();
    }

    /**
     * 抓大厅查询的 SQL 片段。
     *
     * <p>V3.4 起大厅走 {@code orderMapper.selectList(...)}（候选集 → Java 侧打分 →
     * 内存分页），不再是 {@code selectPage}。</p>
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private String hallSql() {
        ArgumentCaptor<Wrapper> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(orderMapper, atLeastOnce()).selectList(captor.capture());
        return captor.getAllValues().get(0).getSqlSegment();
    }

    private CompanionOrder pendingOrder() {
        return pendingOrder(ORDER_ID);
    }

    private CompanionOrder pendingOrder(Long orderId) {
        CompanionOrder order = new CompanionOrder();
        order.setId(orderId);
        order.setOrderNo("NL202609150000" + (orderId - ORDER_ID + 1));
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

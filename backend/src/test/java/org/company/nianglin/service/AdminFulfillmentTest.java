package org.company.nianglin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.company.nianglin.common.ResultCode;
import org.company.nianglin.constant.FulfillmentRisk;
import org.company.nianglin.constant.OrderStatus;
import org.company.nianglin.dto.FulfillmentRiskQuery;
import org.company.nianglin.entity.CompanionOrder;
import org.company.nianglin.entity.ElderProfile;
import org.company.nianglin.exception.BusinessException;
import org.company.nianglin.mapper.AdminReadMapper;
import org.company.nianglin.mapper.CompanionOrderMapper;
import org.company.nianglin.mapper.OrderReadMapper;
import org.company.nianglin.service.impl.AdminServiceImpl;
import org.company.nianglin.support.MybatisLambdaCache;
import org.company.nianglin.vo.AdminFulfillmentRiskItemVO;
import org.company.nianglin.vo.FulfillmentSummaryVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 履约风险中心单测（V3.1 · T6）。
 *
 * <p>三个必须钉死的性质：</p>
 *
 * <ol>
 *   <li><b>四档计数恒定齐全</b> —— 某档为 0 时返回 0 而不是省略字段；
 *       「0 单」和「没统计」在卡片上是两件事；</li>
 *   <li><b>管理端没有第二套风险判定</b> —— 计数读的是 {@code fulfillment_risk} 列
 *       （由 {@code FulfillmentRiskEvaluator} 在扫描时写入），列表读的也是这一列；
 *       本类里任何地方都不重新按等待时长算等级。若有人在管理端另写一套阈值判断，
 *       会出现「卡片 3 单紧急、列表只列 2 单」这种自相矛盾的看板；</li>
 *   <li><b>列表脱敏</b> —— 订单详情的全名豁免不适用于列表。</li>
 * </ol>
 *
 * @author 银龄伴诊团队
 * @since V3.1
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("履约风险中心：四档计数齐全 / 固定排序 / 列表脱敏")
class AdminFulfillmentTest {

    private static final Long ORDER_ID = 1001L;
    private static final Long ELDER_ID = 501L;

    @Mock
    private CompanionOrderMapper orderMapper;

    @Mock
    private AdminReadMapper adminReadMapper;

    @Mock
    private OrderReadMapper orderReadMapper;

    private AdminServiceImpl service;

    @BeforeEach
    void setUp() {
        MybatisLambdaCache.warmUp();
        // 未用到的依赖传 null：本类只走计数与风险列表两条只读路径
        service = new AdminServiceImpl(null, null, null, null, orderMapper, null,
                null, adminReadMapper, orderReadMapper, null, null, null,
                null, null, null, new ObjectMapper());
    }

    /* ================================================================== */
    /* 1 · 四档计数                                                        */
    /* ================================================================== */

    @Test
    @DisplayName("计数 · 四档各有数据时各归各位，且候选集是「只统计待接单」")
    void summaryShouldCountFourRiskLevels() {
        given(adminReadMapper.selectFulfillmentRiskCounts(OrderStatus.PENDING.name()))
                .willReturn(List.of(
                        Map.of("risk", "MANUAL_REQUIRED", "cnt", 1L),
                        Map.of("risk", "URGENT", "cnt", 3L),
                        Map.of("risk", "WARNING", "cnt", 5L),
                        Map.of("risk", "NORMAL", "cnt", 12L)));

        FulfillmentSummaryVO vo = service.fulfillmentSummary();

        assertEquals(1, vo.getManualRequired());
        assertEquals(3, vo.getUrgent());
        assertEquals(5, vo.getWarning());
        assertEquals(12, vo.getNormal());
        // 候选集与列表共用同一条定义（只统计 PENDING）
        verify(adminReadMapper).selectFulfillmentRiskCounts(OrderStatus.PENDING.name());
    }

    @Test
    @DisplayName("计数 · 某档无数据时返回 0，而不是省略该档")
    void summaryShouldReturnZeroForAbsentLevels() {
        given(adminReadMapper.selectFulfillmentRiskCounts(any())).willReturn(List.of(
                Map.of("risk", "URGENT", "cnt", 2L)));

        FulfillmentSummaryVO vo = service.fulfillmentSummary();

        assertEquals(0, vo.getManualRequired(), "缺席的档必须是 0，不能是没算");
        assertEquals(2, vo.getUrgent());
        assertEquals(0, vo.getWarning());
        assertEquals(0, vo.getNormal());
    }

    @Test
    @DisplayName("计数 · 完全没有待接单订单时四档全为 0，不报错")
    void summaryShouldReturnAllZeroOnEmptyResult() {
        given(adminReadMapper.selectFulfillmentRiskCounts(any())).willReturn(List.of());

        FulfillmentSummaryVO vo = service.fulfillmentSummary();

        assertEquals(0, vo.getManualRequired());
        assertEquals(0, vo.getUrgent());
        assertEquals(0, vo.getWarning());
        assertEquals(0, vo.getNormal());
    }

    @Test
    @DisplayName("计数 · 脏值与 NULL 一律并入 normal，保证四档之和 = 待接单总数")
    void summaryShouldBucketDirtyValueIntoNormal() {
        // 列是 NOT NULL DEFAULT，出现这里只能是人工改库；看板必须还能对上账
        given(adminReadMapper.selectFulfillmentRiskCounts(any())).willReturn(List.of(
                Map.of("risk", "BOGUS", "cnt", 2L),
                Map.of("risk", "NORMAL", "cnt", 3L)));

        FulfillmentSummaryVO vo = service.fulfillmentSummary();

        assertEquals(5, vo.getNormal(), "脏值不能凭空消失，否则四档之和 ≠ 待接单总数");
        assertEquals(0, vo.getUrgent());
        assertEquals(0, vo.getWarning());
        assertEquals(0, vo.getManualRequired());
    }

    @Test
    @DisplayName("计数 · 分组行结构异常（缺 cnt / 行为 null）时不抛异常")
    void summaryShouldTolerateMalformedRows() {
        given(adminReadMapper.selectFulfillmentRiskCounts(any()))
                .willReturn(Arrays.<Map<String, Object>>asList(null,
                        Map.of("risk", "WARNING"), Map.of("cnt", 4L)));

        FulfillmentSummaryVO vo = service.fulfillmentSummary();

        // 有 risk 无 cnt：按 0 计入该档（不抛异常，也不凭空造数）
        assertEquals(0, vo.getWarning());
        // 有 cnt 无 risk：没有风险标识的行按脏值并入 normal
        assertEquals(4, vo.getNormal());
    }

    /* ================================================================== */
    /* 2 · 风险列表：筛选与固定排序                                        */
    /* ================================================================== */

    @ParameterizedTest(name = "risk={0} 被拒")
    @ValueSource(strings = {"AT_RISK", "PENDING", "urgent_bypass", "WARN"})
    @DisplayName("列表 · risk 不是四档之一 → 400，且不静默忽略")
    void riskListShouldRejectUnknownRisk(String risk) {
        FulfillmentRiskQuery query = new FulfillmentRiskQuery();
        query.setRisk(risk);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.fulfillmentRiskList(query));

        assertEquals(ResultCode.PARAM_ERROR.getCode(), ex.getCode());
        // 静默忽略会让管理员以为筛过的结果就是全部
        verify(orderMapper, never()).selectPage(any(), any());
    }

    @Test
    @DisplayName("列表 · 不传 risk 时返回全部等级（只保留待接单这一个条件）")
    void riskListShouldReturnAllRisksWithoutFilter() {
        given(orderMapper.selectPage(any(), any())).willReturn(pageOf());

        service.fulfillmentRiskList(new FulfillmentRiskQuery());

        assertTrue(whereClause().startsWith("(status = #{ew.paramNameValuePairs"),
                "缺省只按状态过滤，不追加等级条件，实际：" + whereClause());
        // 参数键名由 MP 生成（MPGENVALn），这里只认值：缺省只带 PENDING 一个条件
        assertEquals(1, riskListParams().size(), "缺省不该多出任何筛选条件：" + riskListParams());
        assertTrue(riskListParams().containsValue(OrderStatus.PENDING.name()));
    }

    @Test
    @DisplayName("列表 · 指定 risk 时叠加等级条件")
    void riskListShouldApplyRiskFilter() {
        given(orderMapper.selectPage(any(), any())).willReturn(pageOf());
        FulfillmentRiskQuery query = new FulfillmentRiskQuery();
        query.setRisk(FulfillmentRisk.URGENT.name());

        service.fulfillmentRiskList(query);

        assertTrue(whereClause().contains("fulfillment_risk = #{ew.paramNameValuePairs"),
                whereClause());
        assertEquals(2, riskListParams().size(), "只应有状态与等级两个条件：" + riskListParams());
        assertTrue(riskListParams().containsValue(OrderStatus.PENDING.name()));
        assertTrue(riskListParams().containsValue("URGENT"));
    }

    @Test
    @DisplayName("列表 · 固定三级排序：等级降序 → 等待时长降序（create_time 升序）→ id 降序")
    void riskListShouldUseFixedThreeLevelOrder() {
        given(orderMapper.selectPage(any(), any())).willReturn(pageOf());

        service.fulfillmentRiskList(new FulfillmentRiskQuery());

        String sql = riskListSql();
        assertTrue(sql.contains("FIELD(fulfillment_risk,'MANUAL_REQUIRED','URGENT','WARNING','NORMAL')"),
                "等级降序必须由 FIELD 的参数顺序表达，实际：" + sql);
        assertTrue(sql.contains("create_time ASC"), "等待时长降序 = 下单时间升序，实际：" + sql);
        assertTrue(sql.contains("id DESC"), "同级要 id 降序兜底，实际：" + sql);
        assertEquals(1, countOccurrences(sql, "ORDER BY"),
                "整段 ORDER BY 只能有一处（last() 与 orderBy* 混用会产出两个 ORDER BY）");
    }

    /* ================================================================== */
    /* 3 · 脱敏与装配                                                      */
    /* ================================================================== */

    @Test
    @DisplayName("列表 · 老人姓名与订单号一律脱敏")
    void riskListShouldMaskNamesAndOrderNo() {
        given(orderMapper.selectPage(any(), any())).willReturn(pageOf(pendingOrder()));
        given(adminReadMapper.selectAdminCoordinatedOrderIds(any())).willReturn(List.of());
        given(orderReadMapper.selectEldersIgnoringLogicDelete(any())).willReturn(List.of(elder()));

        AdminFulfillmentRiskItemVO item = service.fulfillmentRiskList(new FulfillmentRiskQuery())
                .getRecords().get(0);

        assertEquals("张*海", item.getElderName());
        assertFalse(item.getOrderNo().contains("20261017000001"),
                "订单号不能整串出现在列表里，实际：" + item.getOrderNo());
        assertTrue(item.getOrderNo().startsWith("NL20"), item.getOrderNo());
        assertTrue(item.getOrderNo().endsWith("0001"), item.getOrderNo());
    }

    @Test
    @DisplayName("列表 · 等待时长现算、催单次数 NULL 记 0")
    void riskListShouldComputeWaitMinutesAndUrgeCount() {
        CompanionOrder order = pendingOrder();
        order.setUrgeCount(null);
        given(orderMapper.selectPage(any(), any())).willReturn(pageOf(order));
        given(adminReadMapper.selectAdminCoordinatedOrderIds(any())).willReturn(List.of());
        given(orderReadMapper.selectEldersIgnoringLogicDelete(any())).willReturn(List.of());

        AdminFulfillmentRiskItemVO item = service.fulfillmentRiskList(new FulfillmentRiskQuery())
                .getRecords().get(0);

        assertTrue(item.getWaitMinutes() >= 45, "下单已 45 分钟，实际 " + item.getWaitMinutes());
        assertEquals(0, item.getUrgeCount());
        assertNotNull(item.getFulfillmentRiskLabel(), "文案必须由枚举给出，不能是 null");
    }

    @Test
    @DisplayName("列表 · 人工介入标记按批量查询结果点亮，缺席即 false")
    void riskListShouldFlagAdminCoordinated() {
        given(orderMapper.selectPage(any(), any())).willReturn(pageOf(pendingOrder()));
        given(adminReadMapper.selectAdminCoordinatedOrderIds(any()))
                .willReturn(List.of(ORDER_ID));
        given(orderReadMapper.selectEldersIgnoringLogicDelete(any())).willReturn(List.of());

        AdminFulfillmentRiskItemVO item = service.fulfillmentRiskList(new FulfillmentRiskQuery())
                .getRecords().get(0);

        assertTrue(item.getAdminCoordinated());
    }

    @Test
    @DisplayName("列表 · 脏风险值按 NORMAL 展示（与顶部计数同口径）")
    void riskListShouldTreatDirtyRiskAsNormal() {
        CompanionOrder order = pendingOrder();
        order.setFulfillmentRisk("BOGUS");
        given(orderMapper.selectPage(any(), any())).willReturn(pageOf(order));
        given(adminReadMapper.selectAdminCoordinatedOrderIds(any())).willReturn(List.of());
        given(orderReadMapper.selectEldersIgnoringLogicDelete(any())).willReturn(List.of());

        AdminFulfillmentRiskItemVO item = service.fulfillmentRiskList(new FulfillmentRiskQuery())
                .getRecords().get(0);

        assertEquals(FulfillmentRisk.NORMAL.name(), item.getFulfillmentRisk());
    }

    @Test
    @DisplayName("列表 · 空数据返回空列表，且不触发姓名 / 介入标记的批量查询")
    void riskListShouldReturnEmptyListWithoutTouchingLoaders() {
        given(orderMapper.selectPage(any(), any())).willReturn(pageOf());

        assertEquals(0, service.fulfillmentRiskList(new FulfillmentRiskQuery()).getRecords().size());
        verify(orderReadMapper, never()).selectEldersIgnoringLogicDelete(any());
        verify(adminReadMapper, never()).selectAdminCoordinatedOrderIds(any());
        verify(orderMapper, times(1)).selectPage(any(), any());
    }

    /* ================================================================== */
    /* 测试夹具                                                            */
    /* ================================================================== */

    /** 抓风险列表查询的 SQL 片段（含 last() 里的整段 ORDER BY），与 HallSortTest 同源 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private String riskListSql() {
        ArgumentCaptor<LambdaQueryWrapper> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(orderMapper, times(1)).selectPage(any(), captor.capture());
        return captor.getValue().getSqlSegment();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Map<Object, Object> riskListParams() {
        ArgumentCaptor<LambdaQueryWrapper> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(orderMapper, times(1)).selectPage(any(), captor.capture());
        return captor.getValue().getParamNameValuePairs();
    }

    /** WHERE 段：从整段 SQL 里截出 ORDER BY 之前的部分（FIELD 里也含列名，不截会误判） */
    private String whereClause() {
        String sql = riskListSql();
        int orderByIdx = sql.indexOf("ORDER BY");
        return orderByIdx < 0 ? sql : sql.substring(0, orderByIdx).trim();
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
            count++;
        }
        return count;
    }

    private Page<CompanionOrder> pageOf(CompanionOrder... orders) {
        Page<CompanionOrder> page = new Page<>(1, 10, orders.length);
        page.setRecords(List.of(orders));
        return page;
    }

    private CompanionOrder pendingOrder() {
        CompanionOrder order = new CompanionOrder();
        order.setId(ORDER_ID);
        order.setOrderNo("NL20261017000001");
        order.setFamilyId(300L);
        order.setElderId(ELDER_ID);
        order.setCompanionId(null);
        order.setHospital("市第一人民医院");
        order.setDepartment("心内科");
        order.setStatus(OrderStatus.PENDING.name());
        order.setFulfillmentRisk(FulfillmentRisk.WARNING.name());
        order.setUrgeCount(1);
        order.setFee(new BigDecimal("128.00"));
        order.setVisitTime(LocalDateTime.now().plusDays(3));
        order.setCreateTime(LocalDateTime.now().minusMinutes(45));
        return order;
    }

    private ElderProfile elder() {
        ElderProfile elder = new ElderProfile();
        elder.setId(ELDER_ID);
        elder.setUserId(201L);
        elder.setName("张德海");
        return elder;
    }
}

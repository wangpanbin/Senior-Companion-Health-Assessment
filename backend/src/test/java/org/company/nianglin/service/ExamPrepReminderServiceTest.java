package org.company.nianglin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.company.nianglin.constant.MessageType;
import org.company.nianglin.entity.CompanionOrder;
import org.company.nianglin.entity.ElderProfile;
import org.company.nianglin.mapper.CompanionOrderMapper;
import org.company.nianglin.mapper.ElderProfileMapper;
import org.company.nianglin.support.MybatisLambdaCache;
import org.company.nianglin.util.ComplianceCheckUtil;
import org.company.nianglin.util.MessageTemplateUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 就诊前一天检查准备提醒单测（V5 · B，docs/api/14-exam-prep.md §2）。
 *
 * <p>盯四条：<b>窗口</b>（只取明天 visit_time 的在途订单）、
 * <b>收件人</b>（下单家属 + 就诊老人本人）、
 * <b>幂等</b>（Redis SET NX，同订单同就诊日只发一次）、
 * <b>隔离</b>（单订单失败不中断整批）。</p>
 *
 * @author 银龄伴诊团队
 * @since V5
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("检查准备提醒：窗口 / 收件人 / 幂等 / 隔离")
class ExamPrepReminderServiceTest {

    private static final Long ORDER_ID = 1001L;
    private static final Long OTHER_ORDER_ID = 1002L;
    private static final Long FAMILY_ID = 101L;
    private static final Long ELDER_ID = 401L;
    private static final Long ELDER_USER_ID = 201L;

    @Mock
    private CompanionOrderMapper orderMapper;

    @Mock
    private ElderProfileMapper elderMapper;

    @Mock
    private MessageService messageService;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private ExamPrepReminderService service;

    @BeforeEach
    void setUp() {
        MybatisLambdaCache.warmUp();
        service = new ExamPrepReminderService(orderMapper, elderMapper, messageService, redisTemplate);
    }

    @Test
    @DisplayName("明天有订单 → 家属与就诊老人各收一条（bizId=orderId），且窗口只含明天")
    void scanShouldNotifyFamilyAndElderForTomorrowOrder() {
        given(orderMapper.selectPage(any(), any())).willReturn(page(tomorrowOrder(ORDER_ID)));
        given(elderMapper.selectById(ELDER_ID)).willReturn(elder());
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).willReturn(true);

        int sent = service.scanTomorrowOrders();

        assertEquals(1, sent);

        ArgumentCaptor<Map<String, Object>> params = ArgumentCaptor.forClass(Map.class);
        verify(messageService).send(eq(FAMILY_ID), eq(MessageType.ORDER_EXAM_PREP), eq(ORDER_ID), params.capture());
        verify(messageService).send(eq(ELDER_USER_ID), eq(MessageType.ORDER_EXAM_PREP), eq(ORDER_ID), any());
        assertEquals("海南省人民医院", params.getValue().get("hospital"));
        assertTrue(String.valueOf(params.getValue().get("visitDate")).contains("月"), "visitDate 应为人读格式");

        // 窗口口径：visit_time 落在明天 0 点到后天 0 点之间，且只取在途状态
        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<LambdaQueryWrapper> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(orderMapper).selectPage(any(), captor.capture());
        String sql = captor.getValue().getSqlSegment().replaceAll("\\s+", " ");
        assertTrue(sql.contains("visit_time"), "按就诊时间过滤：" + sql);
        assertTrue(sql.contains("status"), "按状态过滤：" + sql);
        assertTrue(captor.getValue().getParamNameValuePairs().containsValue("ACCEPTED"), "含已接单");
        assertTrue(captor.getValue().getParamNameValuePairs().containsValue("PENDING"), "含待接单（还没接单也要提醒）");
    }

    @Test
    @DisplayName("幂等：同订单同就诊日已提醒过（SET NX 未抢到）→ 零发送")
    void scanShouldSkipAlreadyRemindedOrder() {
        given(orderMapper.selectPage(any(), any())).willReturn(page(tomorrowOrder(ORDER_ID)));
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).willReturn(false);

        int sent = service.scanTomorrowOrders();

        assertEquals(0, sent);
        verify(messageService, never()).send(anyLong(), any(MessageType.class), anyLong(), any());
    }

    @Test
    @DisplayName("无明天订单 → 零发送，且不碰 Redis")
    void scanShouldDoNothingWhenNoTomorrowOrders() {
        given(orderMapper.selectPage(any(), any())).willReturn(new Page<>());

        int sent = service.scanTomorrowOrders();

        assertEquals(0, sent);
        verify(messageService, never()).send(anyLong(), any(MessageType.class), anyLong(), any());
        verify(redisTemplate, never()).opsForValue();
    }

    @Test
    @DisplayName("隔离：第一单发送抛异常，第二单照发，扫描不中断")
    void scanShouldIsolateSingleOrderFailure() {
        given(orderMapper.selectPage(any(), any()))
                .willReturn(page(tomorrowOrder(ORDER_ID), tomorrowOrder(OTHER_ORDER_ID)));
        given(elderMapper.selectById(ELDER_ID)).willReturn(elder());
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).willReturn(true);
        // 第一次 send 抛异常（家属侧），后续调用放行（send 有返回值，用 then 链而非 doNothing）
        when(messageService.send(anyLong(), any(MessageType.class), anyLong(), any()))
                .thenThrow(new RuntimeException("站内信写库失败"))
                .thenReturn(null);

        int sent = service.scanTomorrowOrders();

        assertEquals(1, sent, "失败的那单不该计入，成功的那单必须发出去");
        verify(messageService).send(eq(FAMILY_ID), eq(MessageType.ORDER_EXAM_PREP), eq(OTHER_ORDER_ID), any());
    }

    @Test
    @DisplayName("文案：模板渲染后不含医疗建议禁用词（红线-1）")
    void reminderContentShouldPassComplianceCheck() {
        Map<String, Object> params = Map.of(
                "visitDate", "11月15日",
                "hospital", "海南省人民医院",
                "department", "心内科");

        String content = MessageTemplateUtil.content(MessageType.ORDER_EXAM_PREP, params);

        assertNull(ComplianceCheckUtil.firstHit(content), "检查准备提醒文案不应命中合规词表：" + content);
    }

    /* ------------------------------------------------------------------ */

    private Page<CompanionOrder> page(CompanionOrder... orders) {
        Page<CompanionOrder> page = new Page<>();
        page.setRecords(List.of(orders));
        return page;
    }

    /** 就诊时间固定在明天 10:00（窗口断言依赖这个相对值） */
    private CompanionOrder tomorrowOrder(Long id) {
        CompanionOrder order = new CompanionOrder();
        order.setId(id);
        order.setFamilyId(FAMILY_ID);
        order.setElderId(ELDER_ID);
        order.setHospital("海南省人民医院");
        order.setDepartment("心内科");
        order.setStatus("ACCEPTED");
        order.setVisitTime(java.time.LocalDate.now().plusDays(1).atTime(10, 0));
        return order;
    }

    private ElderProfile elder() {
        ElderProfile elder = new ElderProfile();
        elder.setId(ELDER_ID);
        elder.setUserId(ELDER_USER_ID);
        elder.setName("张德海");
        return elder;
    }
}

package org.company.nianglin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.company.nianglin.common.ResultCode;
import org.company.nianglin.constant.OrderStatus;
import org.company.nianglin.constant.RoleConstants;
import org.company.nianglin.dto.MedicationPlanDraftFromOrderDTO;
import org.company.nianglin.entity.CompanionOrder;
import org.company.nianglin.entity.MedicineDict;
import org.company.nianglin.exception.BusinessException;
import org.company.nianglin.mapper.CompanionOrderMapper;
import org.company.nianglin.mapper.ElderProfileMapper;
import org.company.nianglin.mapper.MedicationPlanMapper;
import org.company.nianglin.mapper.MedicationTaskMapper;
import org.company.nianglin.mapper.MedicineDictMapper;
import org.company.nianglin.mapper.SysUserMapper;
import org.company.nianglin.security.LoginUser;
import org.company.nianglin.service.impl.MedicationServiceImpl;
import org.company.nianglin.service.support.medication.ReminderDispatcher;
import org.company.nianglin.support.MybatisLambdaCache;
import org.company.nianglin.vo.MedicationPlanDraftVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * V4 · P1-2「陪诊 → 用药贯通」草稿接口单测。
 *
 * <p>本类锁死三件事：</p>
 * <ol>
 *   <li><b>不生成剂量</b>：请求 / 响应字段集合里都不能出现 dosage 类字段；</li>
 *   <li><b>只有下单家属本人</b>能转，且订单必须 COMPLETED 且有合规的服务小结；</li>
 *   <li><b>一单一转</b>：30 分钟窗口内重复调用 409，Redis 故障放行。</li>
 * </ol>
 *
 * @author 银龄伴诊团队
 * @since V4 · P1-2
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("V4 · P1-2 订单转用药计划草稿：无剂量 / 归属 / 状态 / 判重")
class MedicationDraftFromOrderTest {

    private static final Long FAMILY_ID = 101L;
    private static final Long OTHER_FAMILY_ID = 102L;
    private static final Long ELDER_ID = 425L;
    private static final Long ORDER_ID = 1025L;
    private static final Long MEDICINE_ID = 801L;

    @Mock
    private MedicineDictMapper medicineMapper;
    @Mock
    private MedicationPlanMapper planMapper;
    @Mock
    private MedicationTaskMapper taskMapper;
    @Mock
    private ElderProfileMapper elderMapper;
    @Mock
    private CompanionOrderMapper orderMapper;
    @Mock
    private SysUserMapper sysUserMapper;
    @Mock
    private ElderService elderService;
    @Mock
    private ReminderDispatcher reminderDispatcher;
    @Mock
    private OrderService orderService;
    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;

    private MedicationServiceImpl service;

    @BeforeEach
    void setUp() {
        MybatisLambdaCache.warmUp();
        service = new MedicationServiceImpl(medicineMapper, planMapper, taskMapper, elderMapper,
                orderMapper, sysUserMapper, elderService, reminderDispatcher,
                new ObjectMapper(), orderService, stringRedisTemplate);
        loginAs(FAMILY_ID);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    /* ================================================================== */
    /* 1 · 成功路径 + 无剂量字段                                            */
    /* ================================================================== */

    @Test
    @DisplayName("转草稿 · 成功生成且响应 / 请求字段集合均不含 dosage")
    void shouldBuildDraftWithoutDosage() throws Exception {
        given(orderService.requireInvolved(ORDER_ID)).willReturn(completedOrder("已协助取药，老人精神状态良好"));
        given(medicineMapper.selectList(any())).willReturn(List.of(dict(MEDICINE_ID, "苯磺酸氨氯地平片", null)));
        given(stringRedisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).willReturn(true);

        MedicationPlanDraftVO draft = service.buildDraftFromOrder(ORDER_ID, dto(
                item("苯磺酸氨氯地平片", 1, "08:00")));

        assertEquals(ORDER_ID, draft.getOrderId());
        assertEquals(30, draft.getDraftTtlMinutes());
        assertEquals(1, draft.getItems().size());
        assertEquals(MEDICINE_ID, draft.getItems().get(0).getMedicineId());
        assertTrue(draft.getItems().get(0).getMatched());

        assertNoDosageField(MedicationPlanDraftFromOrderDTO.class);
        assertNoDosageField(MedicationPlanDraftFromOrderDTO.Item.class);
        assertNoDosageField(MedicationPlanDraftVO.class);
        assertNoDosageField(MedicationPlanDraftVO.Item.class);
        String json = new ObjectMapper().writeValueAsString(draft);
        assertFalse(json.contains("dosage"), "响应序列化后不能出现 dosage 字段");
        // 草稿绝不落库
        verify(planMapper, never()).insert(any(org.company.nianglin.entity.MedicationPlan.class));
        verify(taskMapper, never()).insert(any(org.company.nianglin.entity.MedicationTask.class));
    }

    /* ================================================================== */
    /* 2 · 归属：只有下单家属本人                                            */
    /* ================================================================== */

    @Test
    @DisplayName("转草稿 · 非下单家属（哪怕是相关方）→ 3004")
    void nonOwnerFamilyShouldGet3004() {
        CompanionOrder order = completedOrder("已协助取药");
        order.setFamilyId(OTHER_FAMILY_ID);
        given(orderService.requireInvolved(ORDER_ID)).willReturn(order);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.buildDraftFromOrder(ORDER_ID, dto(item("苯磺酸氨氯地平片", 1, "08:00"))));

        assertEquals(ResultCode.ORDER_NO_PERMISSION.getCode(), ex.getCode());
        verify(stringRedisTemplate, never()).opsForValue();
    }

    /* ================================================================== */
    /* 3 ~ 5 · 订单状态 / 小结 / 合规兜底                                    */
    /* ================================================================== */

    @Test
    @DisplayName("转草稿 · 订单未完成 → 409")
    void incompleteOrderShouldGet409() {
        CompanionOrder order = completedOrder("已协助取药");
        order.setStatus(OrderStatus.IN_SERVICE.name());
        given(orderService.requireInvolved(ORDER_ID)).willReturn(order);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.buildDraftFromOrder(ORDER_ID, dto(item("苯磺酸氨氯地平片", 1, "08:00"))));

        assertEquals(ResultCode.CONFLICT.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("转草稿 · 服务小结为空 → 409 且提示「服务小结尚未填写」")
    void blankSummaryShouldGet409() {
        given(orderService.requireInvolved(ORDER_ID)).willReturn(completedOrder("   "));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.buildDraftFromOrder(ORDER_ID, dto(item("苯磺酸氨氯地平片", 1, "08:00"))));

        assertEquals(ResultCode.CONFLICT.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("服务小结尚未填写"));
    }

    @Test
    @DisplayName("转草稿 · 服务小结命中合规词 → 3007（即使完成时已查过也要复检）")
    void illegalSummaryShouldGet3007() {
        given(orderService.requireInvolved(ORDER_ID)).willReturn(completedOrder("医生建议服用阿司匹林"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.buildDraftFromOrder(ORDER_ID, dto(item("阿司匹林", 1, "08:00"))));

        assertEquals(ResultCode.ORDER_SUMMARY_ILLEGAL.getCode(), ex.getCode());
    }

    /* ================================================================== */
    /* 6 · 跨老人串档                                                       */
    /* ================================================================== */

    @Test
    @DisplayName("转草稿 · elderId 与订单不一致 → 400")
    void elderMismatchShouldGet400() {
        given(orderService.requireInvolved(ORDER_ID)).willReturn(completedOrder("已协助取药"));

        MedicationPlanDraftFromOrderDTO request = dto(item("苯磺酸氨氯地平片", 1, "08:00"));
        request.setElderId(401L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.buildDraftFromOrder(ORDER_ID, request));

        assertEquals(ResultCode.PARAM_ERROR.getCode(), ex.getCode());
    }

    /* ================================================================== */
    /* 7 · 药名匹配成功 / 未匹配                                             */
    /* ================================================================== */

    @Test
    @DisplayName("转草稿 · 药名匹配成功 matched=true；未匹配 matched=false 且保留原文，不报错")
    void unmatchedDrugShouldKeepOriginalName() {
        given(orderService.requireInvolved(ORDER_ID)).willReturn(completedOrder("已协助取药"));
        given(medicineMapper.selectList(any())).willReturn(List.of(
                dict(MEDICINE_ID, "苯磺酸氨氯地平片", "络活喜"),
                dict(802L, "阿司匹林肠溶片", null)));
        given(stringRedisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).willReturn(true);

        MedicationPlanDraftVO draft = service.buildDraftFromOrder(ORDER_ID, dto(
                item(" 苯磺酸 氨氯地平片 ", 1, "08:00"),
                item("阿司匹林", 2, "12:00"),
                item("完全不存在的药", 1, "20:00")));

        assertEquals(3, draft.getItems().size());
        assertTrue(draft.getItems().get(0).getMatched());
        assertEquals(MEDICINE_ID, draft.getItems().get(0).getMedicineId());
        // 包含匹配：输入「阿司匹林」命中第一条包含它的字典项
        assertTrue(draft.getItems().get(1).getMatched());
        assertEquals(802L, draft.getItems().get(1).getMedicineId());
        assertFalse(draft.getItems().get(2).getMatched());
        assertNull(draft.getItems().get(2).getMedicineId());
        assertEquals("完全不存在的药", draft.getItems().get(2).getMedicineName());
    }

    /* ================================================================== */
    /* 8 ~ 9 · 判重：窗口内 409 / Redis 故障放行                              */
    /* ================================================================== */

    @Test
    @DisplayName("转草稿 · 30 分钟内重复调用 → 409（SET NX 失败）")
    void duplicatedDraftShouldGet409() {
        given(orderService.requireInvolved(ORDER_ID)).willReturn(completedOrder("已协助取药"));
        given(medicineMapper.selectList(any())).willReturn(List.of(dict(MEDICINE_ID, "苯磺酸氨氯地平片", null)));
        given(stringRedisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).willReturn(false);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.buildDraftFromOrder(ORDER_ID, dto(item("苯磺酸氨氯地平片", 1, "08:00"))));

        assertEquals(ResultCode.CONFLICT.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("已生成过"));
    }

    @Test
    @DisplayName("转草稿 · Redis 异常时放行（草稿不是核心写路径）")
    void redisFailureShouldNotBlockDraft() {
        given(orderService.requireInvolved(ORDER_ID)).willReturn(completedOrder("已协助取药"));
        given(medicineMapper.selectList(any())).willReturn(List.of(dict(MEDICINE_ID, "苯磺酸氨氯地平片", null)));
        given(stringRedisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .willThrow(new RuntimeException("redis down"));

        MedicationPlanDraftVO draft = service.buildDraftFromOrder(ORDER_ID, dto(
                item("苯磺酸氨氯地平片", 1, "08:00")));

        assertEquals(ORDER_ID, draft.getOrderId());
        assertEquals(1, draft.getItems().size());
        assertTrue(draft.getItems().get(0).getMatched());
    }

    /* ================================================================== */

    private CompanionOrder completedOrder(String summary) {
        CompanionOrder order = new CompanionOrder();
        order.setId(ORDER_ID);
        order.setFamilyId(FAMILY_ID);
        order.setElderId(ELDER_ID);
        order.setStatus(OrderStatus.COMPLETED.name());
        order.setServiceSummary(summary);
        return order;
    }

    private static MedicineDict dict(Long id, String name, String tradeName) {
        MedicineDict dict = new MedicineDict();
        dict.setId(id);
        dict.setName(name);
        dict.setTradeName(tradeName);
        return dict;
    }

    private static MedicationPlanDraftFromOrderDTO dto(MedicationPlanDraftFromOrderDTO.Item... items) {
        MedicationPlanDraftFromOrderDTO dto = new MedicationPlanDraftFromOrderDTO();
        dto.setElderId(ELDER_ID);
        dto.setItems(Arrays.asList(items));
        return dto;
    }

    private static MedicationPlanDraftFromOrderDTO.Item item(String drugName, int frequency, String timePoint) {
        MedicationPlanDraftFromOrderDTO.Item item = new MedicationPlanDraftFromOrderDTO.Item();
        item.setDrugName(drugName);
        item.setFrequency(frequency);
        item.setTimePoint(timePoint);
        return item;
    }

    private static void assertNoDosageField(Class<?> type) {
        for (Field field : type.getDeclaredFields()) {
            String name = field.getName().toLowerCase();
            assertFalse(name.contains("dosage") || name.contains("dose"),
                    type.getSimpleName() + " 不能包含剂量字段: " + field.getName());
        }
    }

    private void loginAs(Long userId) {
        LoginUser loginUser = new LoginUser(userId, "tester", RoleConstants.FAMILY, 0, "jti",
                System.currentTimeMillis() + 60000);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, loginUser.authorities()));
    }
}

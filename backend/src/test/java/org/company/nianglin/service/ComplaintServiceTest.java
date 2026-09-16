package org.company.nianglin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.company.nianglin.common.ResultCode;
import org.company.nianglin.constant.OrderStatus;
import org.company.nianglin.constant.RoleConstants;
import org.company.nianglin.dto.ComplaintCreateDTO;
import org.company.nianglin.entity.CompanionOrder;
import org.company.nianglin.entity.Complaint;
import org.company.nianglin.exception.BusinessException;
import org.company.nianglin.mapper.ComplaintMapper;
import org.company.nianglin.mapper.SysUserMapper;
import org.company.nianglin.security.LoginUser;
import org.company.nianglin.service.impl.ComplaintServiceImpl;
import org.company.nianglin.service.support.UserNameResolver;
import org.company.nianglin.support.MybatisLambdaCache;
import org.company.nianglin.vo.ComplaintVO;
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

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 投诉服务单测（M7）。
 *
 * <h3>为什么这个类里没有一条「角色越权」用例</h3>
 *
 * <p>投诉的三个接口刻意不写 {@code @PreAuthorize} —— 它的可见范围由<b>订单关系</b>
 * 决定（家属投诉陪诊员 / 陪诊员投诉家属），角色集合虽然是
 * {@code FAMILY ∪ COMPANION}，但注解判断不了「这笔订单是不是你的」。
 * 所以「谁能投诉 / 谁能看」全部落在本类的方法里，
 * 而角色层的 403 用例已经由 {@code ReviewAccessMatrixTest} 从 HTTP 面覆盖。</p>
 *
 * <h3>本类盯的三处</h3>
 *
 * <ol>
 *   <li><b>投诉人与被投诉人必须由订单推导</b>。入参里压根没有这两个字段
 *       （{@code ComplaintCreateDTO} 只有 orderId / type / content / evidence），
 *       本类用 {@code ArgumentCaptor} 把「落库的那两条 ID 确实是按订单关系算出来的」
 *       钉住 —— 一旦有人给 DTO 加回 {@code targetUserId} 并直接用，这里会红。</li>
 *   <li><b>「还没有服务方」要单独报 409</b>，不能写出一条 {@code target_user_id}
 *       为空的投诉 —— 那种记录管理员点开也不知道该找谁。</li>
 *   <li><b>越权 403 与不存在 6004 必须分开</b>。合成一个码，排查时
 *       「这条投诉真的没了」和「这条不给我看」在日志里长得一模一样。</li>
 * </ol>
 *
 * @author 银龄伴诊团队
 * @since M7
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("投诉服务：当事人推导 / 未接单 409 / 越权 403 与 6004 分离")
class ComplaintServiceTest {

    private static final Long ORDER_ID = 1020L;
    private static final Long FAMILY_ID = 101L;
    private static final Long COMPANION_ID = 301L;
    private static final Long OTHER_USER = 999L;
    private static final Long ADMIN_ID = 1L;

    @Mock
    private ComplaintMapper complaintMapper;

    @Mock
    private SysUserMapper sysUserMapper;

    @Mock
    private OrderService orderService;

    @Mock
    private MessageService messageService;

    @Mock
    private UserNameResolver userNameResolver;

    private ComplaintServiceImpl service;

    @BeforeEach
    void setUp() {
        MybatisLambdaCache.warmUp();
        service = new ComplaintServiceImpl(complaintMapper, sysUserMapper, orderService,
                messageService, userNameResolver, new ObjectMapper());
        loginAs(RoleConstants.FAMILY, FAMILY_ID);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    /* ================================================================== */
    /* 1 · 提交投诉：当事人由订单推导，不接受前端传入                          */
    /* ================================================================== */

    @Test
    @DisplayName("提交投诉 · 家属投诉：投诉人 = 下单家属，被投诉人 = 订单的陪诊员")
    void familyComplaintShouldDeriveBothParties() {
        given(orderService.requireInvolved(ORDER_ID)).willReturn(order());
        given(complaintMapper.selectCount(any())).willReturn(0L);

        ComplaintCreateDTO dto = complaintDto("LATE", "陪诊员比约定时间晚了 40 分钟到达，老人错过了取号。");
        dto.setEvidence(List.of("/uploads/202609/cp01.png"));
        service.create(dto);

        Complaint c = captureInsertedComplaint();
        assertEquals(FAMILY_ID, c.getComplainantId());
        assertEquals(RoleConstants.FAMILY, c.getComplainantRole());
        assertEquals(COMPANION_ID, c.getTargetUserId(),
                "被投诉人必须等于订单的陪诊员，绝不能来自请求体");
        assertEquals(RoleConstants.COMPANION, c.getTargetRole());
        assertEquals("PENDING", c.getStatus());
        assertEquals(0, c.getPenaltyToTarget(), "刚提交时不该预设处罚");
    }

    @Test
    @DisplayName("提交投诉 · 陪诊员投诉：当事人自动反转为「陪诊员投诉家属」")
    void companionComplaintShouldReverseTheParties() {
        loginAs(RoleConstants.COMPANION, COMPANION_ID);
        given(orderService.requireInvolved(ORDER_ID)).willReturn(order());
        given(complaintMapper.selectCount(any())).willReturn(0L);

        service.create(complaintDto("FEE_DISPUTE", "线下结算时家属要求多付的费用并未在下单时说明。"));

        Complaint c = captureInsertedComplaint();
        assertEquals(COMPANION_ID, c.getComplainantId());
        assertEquals(RoleConstants.COMPANION, c.getComplainantRole());
        assertEquals(FAMILY_ID, c.getTargetUserId());
        assertEquals(RoleConstants.FAMILY, c.getTargetRole());
    }

    @Test
    @DisplayName("提交投诉 · 订单还没人接单 → 409（写一条没有被投诉人的投诉，管理员无从下手）")
    void complaintOnUnacceptedOrderShouldBeConflict() {
        CompanionOrder pending = order();
        pending.setCompanionId(null);
        given(orderService.requireInvolved(ORDER_ID)).willReturn(pending);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.create(complaintDto("LATE", "一直没有人来接单，也没人联系我。")));

        assertEquals(ResultCode.CONFLICT.getCode(), ex.getCode());
        verify(complaintMapper, never()).insert(any(Complaint.class));
    }

    @Test
    @DisplayName("提交投诉 · 与订单无关的人 → 3004（管理员也不能以当事人身份发起）")
    void unrelatedUserShouldGet3004() {
        loginAs(RoleConstants.ADMIN, ADMIN_ID);
        given(orderService.requireInvolved(ORDER_ID)).willReturn(order());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.create(complaintDto("LATE", "管理员试图以当事人身份发起投诉。")));

        // 管理员处理投诉走 M9 的处置接口（会写 admin_oper_log）；
        // 允许他在这里发起，等于开了一条不留痕的栽赃通道
        assertEquals(ResultCode.ORDER_NO_PERMISSION.getCode(), ex.getCode());
        verify(complaintMapper, never()).insert(any(Complaint.class));
    }

    @Test
    @DisplayName("提交投诉 · 同一订单已有未结案投诉 → 409（不做重复受理）")
    void duplicateOpenComplaintShouldBeConflict() {
        given(orderService.requireInvolved(ORDER_ID)).willReturn(order());
        given(complaintMapper.selectCount(any())).willReturn(1L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.create(complaintDto("LATE", "陪诊员比约定时间晚了 40 分钟到达。")));

        assertEquals(ResultCode.CONFLICT.getCode(), ex.getCode());
        verify(complaintMapper, never()).insert(any(Complaint.class));
    }

    @Test
    @DisplayName("提交投诉 · 命中敏感词 → 6003，且不落库")
    void sensitiveComplaintShouldBeRejected() {
        given(orderService.requireInvolved(ORDER_ID)).willReturn(order());
        given(complaintMapper.selectCount(any())).willReturn(0L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.create(complaintDto("ATTITUDE", "这个陪诊员就是个骗子，全程敷衍老人。")));

        assertEquals(ResultCode.CONTENT_SENSITIVE.getCode(), ex.getCode());
        verify(complaintMapper, never()).insert(any(Complaint.class));
    }

    @Test
    @DisplayName("提交投诉 · 投诉类型不在枚举里 → 400")
    void unknownTypeShouldBeRejected() {
        given(orderService.requireInvolved(ORDER_ID)).willReturn(order());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.create(complaintDto("NOT_A_TYPE", "陪诊员比约定时间晚了 40 分钟。")));

        assertEquals(ResultCode.PARAM_ERROR.getCode(), ex.getCode());
    }

    /* ================================================================== */
    /* 2 · 投诉详情：越权与不存在必须分开                                     */
    /* ================================================================== */

    @Test
    @DisplayName("投诉详情 · 记录不存在 → 6004")
    void missingComplaintShouldReturn6004() {
        given(complaintMapper.selectById(3001L)).willReturn(null);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.detail(3001L));
        assertEquals(ResultCode.COMPLAINT_NOT_FOUND.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("投诉详情 · 与投诉无关的人 → 403（不是 6004）")
    void unrelatedUserShouldGet403() {
        loginAs(RoleConstants.FAMILY, OTHER_USER);
        given(complaintMapper.selectById(3001L)).willReturn(complaint("PENDING", 0));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.detail(3001L));

        assertEquals(ResultCode.FORBIDDEN.getCode(), ex.getCode(),
                "「不给我看」与「确实没了」必须用不同的码，否则排查时无从下手");
    }

    @Test
    @DisplayName("投诉详情 · 投诉人本人可见 → 未结案时不返回处理结果")
    void complainantShouldSeeDetailWithoutResult() {
        Complaint pending = complaint("PENDING", 0);
        pending.setHandleResult("草稿：拟警告");
        given(complaintMapper.selectById(3001L)).willReturn(pending);

        ComplaintVO vo = service.detail(3001L);

        assertNotNull(vo);
        assertEquals("PENDING", vo.getStatus());
        // 处理意见一旦被前端渲染出去，用户会以为投诉已经有结果了
        assertNull(vo.getHandleResult(), "未结案不得下发处理结论");
    }

    @Test
    @DisplayName("投诉详情 · 被投诉人本人也可见 → 未结案同样不返回处理结果")
    void targetShouldSeeDetail() {
        loginAs(RoleConstants.COMPANION, COMPANION_ID);
        given(complaintMapper.selectById(3001L)).willReturn(complaint("PROCESSING", 0));

        assertEquals("PROCESSING", service.detail(3001L).getStatus());
    }

    @Test
    @DisplayName("投诉详情 · 已结案（RESOLVED）时才下发处理结论")
    void closedComplaintShouldExposeHandlingResult() {
        Complaint closed = complaint("RESOLVED", 1);
        closed.setHandleResult("已核实，对陪诊员作出警告并退还部分服务费");
        given(complaintMapper.selectById(3001L)).willReturn(closed);

        ComplaintVO vo = service.detail(3001L);

        assertEquals("RESOLVED", vo.getStatus());
        assertEquals("已核实，对陪诊员作出警告并退还部分服务费", vo.getHandleResult());
    }

    @Test
    @DisplayName("投诉详情 · 管理员可见任意投诉（纠纷处理需要全量）")
    void adminShouldSeeAnyComplaint() {
        loginAs(RoleConstants.ADMIN, ADMIN_ID);
        given(complaintMapper.selectById(3001L)).willReturn(complaint("PENDING", 0));

        assertNotNull(service.detail(3001L));
    }

    /* ================================================================== */

    private Complaint complaint(String status, int penalty) {
        Complaint complaint = new Complaint();
        complaint.setId(3001L);
        complaint.setOrderId(ORDER_ID);
        complaint.setOrderNo("NL20260906000020");
        complaint.setComplainantId(FAMILY_ID);
        complaint.setComplainantRole(RoleConstants.FAMILY);
        complaint.setTargetUserId(COMPANION_ID);
        complaint.setTargetRole(RoleConstants.COMPANION);
        complaint.setType("ATTITUDE");
        complaint.setContent("陪诊过程中态度不好，老人反复问路时显得很不耐烦。");
        complaint.setStatus(status);
        complaint.setPenaltyToTarget(penalty);
        return complaint;
    }

    private CompanionOrder order() {
        CompanionOrder order = new CompanionOrder();
        order.setId(ORDER_ID);
        order.setOrderNo("NL20260906000020");
        order.setFamilyId(FAMILY_ID);
        order.setElderId(420L);
        order.setCompanionId(COMPANION_ID);
        order.setStatus(OrderStatus.IN_SERVICE.name());
        return order;
    }

    private ComplaintCreateDTO complaintDto(String type, String content) {
        ComplaintCreateDTO dto = new ComplaintCreateDTO();
        dto.setOrderId(ORDER_ID);
        dto.setType(type);
        dto.setContent(content);
        return dto;
    }

    private Complaint captureInsertedComplaint() {
        ArgumentCaptor<Complaint> captor = ArgumentCaptor.forClass(Complaint.class);
        verify(complaintMapper).insert(captor.capture());
        return captor.getValue();
    }

    private void loginAs(String role, Long userId) {
        LoginUser loginUser = new LoginUser(userId, "tester", role, 0, "jti",
                System.currentTimeMillis() + 60000);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, loginUser.authorities()));
    }
}

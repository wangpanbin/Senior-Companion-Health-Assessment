package org.company.nianglin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.company.nianglin.common.ResultCode;
import org.company.nianglin.constant.FeedbackStatus;
import org.company.nianglin.constant.MessageType;
import org.company.nianglin.constant.OperTargetType;
import org.company.nianglin.constant.OperType;
import org.company.nianglin.constant.RoleConstants;
import org.company.nianglin.dto.AdminFeedbackQuery;
import org.company.nianglin.dto.FeedbackCreateDTO;
import org.company.nianglin.dto.FeedbackHandleDTO;
import org.company.nianglin.dto.FeedbackQuery;
import org.company.nianglin.common.PageResult;
import org.company.nianglin.entity.ProductFeedback;
import org.company.nianglin.entity.SysUser;
import org.company.nianglin.exception.BusinessException;
import org.company.nianglin.mapper.ProductFeedbackMapper;
import org.company.nianglin.mapper.SysUserMapper;
import org.company.nianglin.security.LoginUser;
import org.company.nianglin.service.impl.FeedbackServiceImpl;
import org.company.nianglin.service.support.OperLogRecorder;
import org.company.nianglin.service.support.UserNameResolver;
import org.company.nianglin.support.MybatisLambdaCache;
import org.company.nianglin.vo.FeedbackCreateResultVO;
import org.company.nianglin.vo.FeedbackHandleResultVO;
import org.company.nianglin.vo.FeedbackVO;
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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 产品反馈服务单测（V6）。
 *
 * <h3>本类钉住的五处（对应用户明确确认过的口径）</h3>
 *
 * <ol>
 *   <li><b>提交人与角色只能来自登录态</b>：{@code FeedbackCreateDTO} 里压根没有这两个字段，
 *       本类用 {@code ArgumentCaptor} 钉住落库值确实取自 {@code LoginUser}。</li>
 *   <li><b>入参松、出参紧</b>：用户正文写「用药页能不能提醒别吃错」必须放行
 *       （不过医疗词表）；管理员处理说明写「建议您调整剂量」必须 6003。
 *       这一条是本功能最关键、也最容易被"统一一下口径"改坏的断言。</li>
 *   <li><b>只有终态发站内信</b>：{@code PROCESSING} 不发，{@code RESOLVED} / {@code REJECTED} 才发。</li>
 *   <li><b>处理说明只在终态下发给提交人</b>：中间态那段是工作草稿。</li>
 *   <li><b>不存在与无权访问同码 12001</b>：区分开就等于提供了按 ID 枚举的探针。</li>
 * </ol>
 *
 * @author 银龄伴诊团队
 * @since V6 · 产品反馈
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("产品反馈服务：登录态取身份 / 入参松出参紧 / 终态才发信 / 12001 语义")
class FeedbackServiceTest {

    private static final Long SUBMITTER_ID = 101L;
    private static final Long OTHER_FAMILY_ID = 102L;
    private static final Long ADMIN_ID = 1L;
    private static final Long FEEDBACK_ID = 5001L;
    private static final String VALID_CONTENT = "订单列表的字有点小，老人模式下也还是偏小，希望能再大一号。";

    @Mock
    private ProductFeedbackMapper feedbackMapper;

    @Mock
    private SysUserMapper sysUserMapper;

    @Mock
    private MessageService messageService;

    @Mock
    private UserNameResolver userNameResolver;

    @Mock
    private OperLogRecorder operLogRecorder;

    private FeedbackServiceImpl service;

    @BeforeEach
    void setUp() {
        MybatisLambdaCache.warmUp();
        service = new FeedbackServiceImpl(feedbackMapper, sysUserMapper, messageService,
                userNameResolver, operLogRecorder, new ObjectMapper());
        loginAs(RoleConstants.FAMILY, SUBMITTER_ID);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    /* ================================================================== */
    /* 1 · 提交：身份取自登录态                                             */
    /* ================================================================== */

    @Test
    @DisplayName("提交反馈 · 提交人与角色取自登录态，初始状态 PENDING")
    void createShouldDeriveSubmitterFromLogin() {
        FeedbackCreateDTO dto = dto("EXPERIENCE", VALID_CONTENT);
        dto.setImages(List.of("/uploads/202611/a.png", "/uploads/202611/a.png", "  "));

        FeedbackCreateResultVO result = service.create(dto);

        ProductFeedback saved = captureInserted();
        assertEquals(SUBMITTER_ID, saved.getSubmitterId(), "提交人必须等于登录用户，不能来自请求体");
        assertEquals(RoleConstants.FAMILY, saved.getSubmitterRole());
        assertEquals("EXPERIENCE", saved.getType());
        assertEquals(VALID_CONTENT, saved.getContent());
        assertEquals(FeedbackStatus.PENDING.name(), saved.getStatus());
        // 重复 URL 去重、空白项丢弃
        assertEquals("[\"/uploads/202611/a.png\"]", saved.getImages());
        assertEquals("PENDING", result.getStatus());
        assertEquals("待处理", result.getStatusLabel());
    }

    @Test
    @DisplayName("提交反馈 · 陪诊员也能提交，角色如实落库")
    void companionCanSubmit() {
        loginAs(RoleConstants.COMPANION, 301L);

        service.create(dto("DEFECT", "接单大厅刷新之后滚动位置会跳回顶部，翻到第十条很难受。"));

        ProductFeedback saved = captureInserted();
        assertEquals(RoleConstants.COMPANION, saved.getSubmitterRole());
        assertEquals(301L, saved.getSubmitterId());
    }

    @Test
    @DisplayName("提交反馈 · 分类非法 → 400，且不落库")
    void illegalTypeShouldBeRejected() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.create(dto("PRODUCT", VALID_CONTENT)));

        assertEquals(ResultCode.PARAM_ERROR.getCode(), ex.getCode());
        verify(feedbackMapper, never()).insert(any(ProductFeedback.class));
    }

    @Test
    @DisplayName("提交反馈 · 命中敏感词 → 6003，且不落库")
    void sensitiveContentShouldBeRejected() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.create(dto("OTHER", "这个垃圾系统我真是服了，滚吧。")));

        assertEquals(ResultCode.CONTENT_SENSITIVE.getCode(), ex.getCode());
        verify(feedbackMapper, never()).insert(any(ProductFeedback.class));
    }

    @Test
    @DisplayName("提交反馈 · 正文含「用药」类表述必须放行（入参只过敏感词，不过医疗红线词表）")
    void userContentAboutMedicationMustNotBeBlockedByComplianceWordList() {
        // 「建议服用」是医疗红线词表里的词；用户用它提产品建议是正当的。
        // 若有人把 ComplianceCheckUtil 也套到入参上，这条会红。
        String content = "用药页能不能在老人模式下把药名放大，并提示「建议服用」时段，避免吃错。";

        service.create(dto("FEATURE", content));

        assertEquals(content, captureInserted().getContent());
    }

    @Test
    @DisplayName("提交反馈 · 截图超过 3 张由 Bean Validation 拦（服务层只兜底截断，不报错）")
    void moreThanThreeImagesShouldBeTruncatedByService() {
        // DTO 上的 @Size(max=3) 在 Controller 层已经拦掉；直接调 Service 时
        // 应当按「不多存一张」的保守口径截断，而不是写进库再让前端发现超了
        FeedbackCreateDTO dto = dto("OTHER", VALID_CONTENT);
        dto.setImages(List.of("/uploads/202611/1.png", "/uploads/202611/2.png",
                "/uploads/202611/3.png", "/uploads/202611/4.png"));

        service.create(dto);

        String images = captureInserted().getImages();
        assertEquals(3, images.split(",").length, "四张里只应留下三张，实际：" + images);
    }

    /* ================================================================== */
    /* 2 · 详情：12001 的语义                                              */
    /* ================================================================== */

    @Test
    @DisplayName("详情 · 记录不存在 → 12001")
    void detailNotFoundShouldBe12001() {
        given(feedbackMapper.selectById(FEEDBACK_ID)).willReturn(null);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.detail(FEEDBACK_ID));

        assertEquals(ResultCode.FEEDBACK_NOT_FOUND.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("详情 · 他人提交 → 12001（与「不存在」同码，避免按 ID 枚举）")
    void detailOtherUsersFeedbackShouldAlsoBe12001() {
        ProductFeedback othersFeedback = feedback("PENDING", null);
        othersFeedback.setSubmitterId(OTHER_FAMILY_ID);
        given(feedbackMapper.selectById(FEEDBACK_ID)).willReturn(othersFeedback);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.detail(FEEDBACK_ID));

        assertEquals(ResultCode.FEEDBACK_NOT_FOUND.getCode(), ex.getCode(),
                "越权与不存在必须同码，否则等于给了一个探测他人反馈是否存在的水位线");
    }

    @Test
    @DisplayName("详情 · 管理员可看任意反馈")
    void adminCanSeeAnyFeedback() {
        loginAs(RoleConstants.ADMIN, ADMIN_ID);
        given(feedbackMapper.selectById(FEEDBACK_ID)).willReturn(feedback("RESOLVED", "已排入下一版。"));
        given(userNameResolver.resolveAll(any())).willReturn(Map.of(SUBMITTER_ID, "张三"));

        FeedbackVO vo = service.detail(FEEDBACK_ID);

        assertEquals(FEEDBACK_ID, vo.getId());
        assertEquals("已采纳", vo.getStatusLabel());
        assertEquals("已排入下一版。", vo.getHandleResult());
        // 姓名脱敏：张* 而不是张三
        assertEquals("张*", vo.getSubmitterName());
    }

    /* ================================================================== */
    /* 3 · VO 口径：处理说明只在终态下发                                    */
    /* ================================================================== */

    @Test
    @DisplayName("我的反馈 · PROCESSING 阶段不下发处理说明，RESOLVED 才下发")
    void handleResultShouldOnlyBeExposedOnTerminal() {
        given(feedbackMapper.selectPage(any(), any()))
                .willReturn(pageOf(feedback("PROCESSING", "内部草稿：先看看排期"), feedback("RESOLVED", "已排入下一版")));
        given(userNameResolver.resolveAll(any())).willReturn(Map.of(SUBMITTER_ID, "张三"));

        PageResult<FeedbackVO> result = service.myList(new FeedbackQuery());

        assertNull(result.getRecords().get(0).getHandleResult(),
                "处理中的说明是工作草稿，渲染给用户会让人以为已有结论");
        assertEquals("已排入下一版", result.getRecords().get(1).getHandleResult());
    }

    @Test
    @DisplayName("我的反馈 · 状态筛选非法 → 400（不静默忽略，否则前端以为筛选生效了）")
    void illegalStatusFilterShouldBeRejected() {
        FeedbackQuery query = new FeedbackQuery();
        query.setStatus("CLOSED");

        BusinessException ex = assertThrows(BusinessException.class, () -> service.myList(query));

        assertEquals(ResultCode.PARAM_ERROR.getCode(), ex.getCode());
        verifyNoInteractions(userNameResolver);
    }

    /* ================================================================== */
    /* 4 · 处理：状态机 + 双词表 + 终态发信                                 */
    /* ================================================================== */

    @Test
    @DisplayName("处理反馈 · 待处理直接跳「已采纳」→ 409（不许跳级）")
    void skipTransitionShouldBeConflict() {
        given(feedbackMapper.selectById(FEEDBACK_ID)).willReturn(feedback("PENDING", null));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.handle(FEEDBACK_ID, handleDto("RESOLVED", "已排入下一版：字号 +2px。")));

        assertEquals(ResultCode.CONFLICT.getCode(), ex.getCode());
        verify(messageService, never()).send(anyLong(), any(), anyLong(), any());
    }

    @Test
    @DisplayName("处理反馈 · 已采纳回退「处理中」→ 409（不许回退）")
    void rollbackTransitionShouldBeConflict() {
        given(feedbackMapper.selectById(FEEDBACK_ID)).willReturn(feedback("RESOLVED", "已采纳"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.handle(FEEDBACK_ID, handleDto("PROCESSING", "想再改一下结论，重新看一遍。")));

        assertEquals(ResultCode.CONFLICT.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("处理反馈 · 目标状态非法 → 400")
    void illegalTargetStatusShouldBe400() {
        given(feedbackMapper.selectById(FEEDBACK_ID)).willReturn(feedback("PENDING", null));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.handle(FEEDBACK_ID, handleDto("PENDING", "想把已处理的退回待处理。")));

        assertEquals(ResultCode.CONFLICT.getCode(), ex.getCode(),
                "同态 PENDING → PENDING 应先命中「已处于该状态」的 409");
    }

    @Test
    @DisplayName("处理反馈 · 处理说明含医疗建议类表述 → 6003（出参过两套词表，最关键的一条）")
    void medicalAdviceInHandleResultShouldBeRejected() {
        loginAs(RoleConstants.ADMIN, ADMIN_ID);
        given(feedbackMapper.selectById(FEEDBACK_ID)).willReturn(feedback("PROCESSING", "处理中草稿"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.handle(FEEDBACK_ID, handleDto("RESOLVED", "感谢建议，建议您调整剂量后重新提交。")));

        assertEquals(ResultCode.CONTENT_SENSITIVE.getCode(), ex.getCode(),
                "处理说明会经站内信原样回给用户，属于平台产出口径，必须过医疗红线词表");
        verify(feedbackMapper, never()).update(any(), any());
        verify(messageService, never()).send(anyLong(), any(), anyLong(), any());
    }

    @Test
    @DisplayName("处理反馈 · 处理说明含辱骂词 → 6003")
    void sensitiveHandleResultShouldBeRejected() {
        loginAs(RoleConstants.ADMIN, ADMIN_ID);
        given(feedbackMapper.selectById(FEEDBACK_ID)).willReturn(feedback("PROCESSING", "处理中草稿"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.handle(FEEDBACK_ID, handleDto("RESOLVED", "这种垃圾建议以后别再提了，浪费人力。")));

        assertEquals(ResultCode.CONTENT_SENSITIVE.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("处理反馈 · 流转到「处理中」不发站内信")
    void processingShouldNotNotifySubmitter() {
        loginAs(RoleConstants.ADMIN, ADMIN_ID);
        given(feedbackMapper.selectById(FEEDBACK_ID)).willReturn(feedback("PENDING", null));
        given(feedbackMapper.update(any(), any())).willReturn(1);

        service.handle(FEEDBACK_ID, handleDto("PROCESSING", "已接手，本周内给结论，先记一下排期。"));
        verify(messageService, never()).send(anyLong(), eq(MessageType.FEEDBACK_HANDLED), anyLong(), any());
    }

    @Test
    @DisplayName("处理反馈 · 流转到终态才发站内信，且落操作日志")
    void terminalShouldNotifyAndRecordOperLog() {
        loginAs(RoleConstants.ADMIN, ADMIN_ID);
        given(feedbackMapper.selectById(FEEDBACK_ID)).willReturn(feedback("PROCESSING", "处理中草稿"));
        given(feedbackMapper.update(any(), any())).willReturn(1);

        String handleResult = "已排入下一版：订单列表与用药页字号各放大 2px，老人模式同步生效。";
        FeedbackHandleResultVO result = service.handle(FEEDBACK_ID, handleDto("RESOLVED", handleResult));

        assertEquals("RESOLVED", result.getStatus());
        assertEquals("已采纳", result.getStatusLabel());
        assertNotNull(result.getHandleTime());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> paramsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(messageService).send(eq(SUBMITTER_ID), eq(MessageType.FEEDBACK_HANDLED),
                eq(FEEDBACK_ID), paramsCaptor.capture());
        assertEquals(handleResult, paramsCaptor.getValue().get("handleResult"),
                "未超 100 字时不截断");

        verify(operLogRecorder).record(OperType.HANDLE_FEEDBACK, OperTargetType.FEEDBACK, FEEDBACK_ID,
                "反馈 #" + FEEDBACK_ID, "PROCESSING", "RESOLVED", handleResult);
    }

    @Test
    @DisplayName("处理反馈 · 超长处理说明在站内信里截断到 100 字（模板不再二次截断）")
    void longHandleResultShouldBeDigestedForMessage() {
        loginAs(RoleConstants.ADMIN, ADMIN_ID);
        given(feedbackMapper.selectById(FEEDBACK_ID)).willReturn(feedback("PROCESSING", "处理中草稿"));
        given(feedbackMapper.update(any(), any())).willReturn(1);

        String longResult = "已排入下一版。".repeat(30); // 210 字，且不含任何医疗建议类表述
        service.handle(FEEDBACK_ID, handleDto("REJECTED", longResult));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> paramsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(messageService).send(eq(SUBMITTER_ID), eq(MessageType.FEEDBACK_HANDLED),
                eq(FEEDBACK_ID), paramsCaptor.capture());
        String digest = (String) paramsCaptor.getValue().get("handleResult");
        assertEquals(101, digest.length(), "100 字 + 一个省略号");
        assertTrue(digest.endsWith("…"));
    }

    @Test
    @DisplayName("处理反馈 · 并发下条件更新影响 0 行 → 409（两个管理员同时点）")
    void concurrentHandleShouldBeConflict() {
        loginAs(RoleConstants.ADMIN, ADMIN_ID);
        given(feedbackMapper.selectById(FEEDBACK_ID)).willReturn(feedback("PENDING", null));
        given(feedbackMapper.update(any(), any())).willReturn(0);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.handle(FEEDBACK_ID, handleDto("PROCESSING", "已接手，正在核实是哪一页的字号问题。")));

        assertEquals(ResultCode.CONFLICT.getCode(), ex.getCode());
        verify(messageService, never()).send(anyLong(), any(), anyLong(), any());
    }

    @Test
    @DisplayName("处理反馈 · 记录不存在 → 12001")
    void handleNotFoundShouldBe12001() {
        given(feedbackMapper.selectById(FEEDBACK_ID)).willReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.handle(FEEDBACK_ID, handleDto("PROCESSING", "已接手，正在核实。")));

        assertEquals(ResultCode.FEEDBACK_NOT_FOUND.getCode(), ex.getCode());
    }

    /* ================================================================== */
    /* 5 · 管理端列表                                                      */
    /* ================================================================== */

    @Test
    @DisplayName("管理端列表 · 分类筛选非法 → 400")
    void adminListIllegalTypeShouldBe400() {
        AdminFeedbackQuery query = new AdminFeedbackQuery();
        query.setType("PRODUCT");

        BusinessException ex = assertThrows(BusinessException.class, () -> service.adminList(query));

        assertEquals(ResultCode.PARAM_ERROR.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("管理端列表 · 日期格式非法 / 起止颠倒 → 400")
    void adminListIllegalDateRangeShouldBe400() {
        AdminFeedbackQuery bad = new AdminFeedbackQuery();
        bad.setStartDate("2026/11/01");
        assertEquals(ResultCode.PARAM_ERROR.getCode(),
                assertThrows(BusinessException.class, () -> service.adminList(bad)).getCode());

        AdminFeedbackQuery reversed = new AdminFeedbackQuery();
        reversed.setStartDate("2026-11-30");
        reversed.setEndDate("2026-11-01");
        assertEquals(ResultCode.PARAM_ERROR.getCode(),
                assertThrows(BusinessException.class, () -> service.adminList(reversed)).getCode());
    }

    /* ================================================================== */
    /* 夹具                                                                */
    /* ================================================================== */

    private FeedbackCreateDTO dto(String type, String content) {
        FeedbackCreateDTO dto = new FeedbackCreateDTO();
        dto.setType(type);
        dto.setContent(content);
        return dto;
    }

    private FeedbackHandleDTO handleDto(String status, String handleResult) {
        FeedbackHandleDTO dto = new FeedbackHandleDTO();
        dto.setStatus(status);
        dto.setHandleResult(handleResult);
        return dto;
    }

    private ProductFeedback feedback(String status, String handleResult) {
        ProductFeedback f = new ProductFeedback();
        f.setId(FEEDBACK_ID);
        f.setSubmitterId(SUBMITTER_ID);
        f.setSubmitterRole(RoleConstants.FAMILY);
        f.setType("EXPERIENCE");
        f.setContent(VALID_CONTENT);
        f.setStatus(status);
        f.setHandleResult(handleResult);
        return f;
    }

    private ProductFeedback captureInserted() {
        ArgumentCaptor<ProductFeedback> captor = ArgumentCaptor.forClass(ProductFeedback.class);
        verify(feedbackMapper).insert(captor.capture());
        return captor.getValue();
    }

    @SuppressWarnings("unchecked")
    private static <T> com.baomidou.mybatisplus.extension.plugins.pagination.Page<T> pageOf(T... rows) {
        var page = new com.baomidou.mybatisplus.extension.plugins.pagination.Page<T>(1, 10);
        page.setRecords(List.of(rows));
        page.setTotal(rows.length);
        return page;
    }

    private void loginAs(String role, Long userId) {
        LoginUser loginUser = new LoginUser(userId, "tester", role, 0, "jti",
                System.currentTimeMillis() + 60000);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, loginUser.authorities()));
    }
}

package org.company.nianglin.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.company.nianglin.common.PageResult;
import org.company.nianglin.common.ResultCode;
import org.company.nianglin.constant.FeedbackStatus;
import org.company.nianglin.constant.FeedbackType;
import org.company.nianglin.constant.MessageType;
import org.company.nianglin.constant.OperTargetType;
import org.company.nianglin.constant.OperType;
import org.company.nianglin.dto.AdminFeedbackQuery;
import org.company.nianglin.dto.FeedbackCreateDTO;
import org.company.nianglin.dto.FeedbackHandleDTO;
import org.company.nianglin.dto.FeedbackQuery;
import org.company.nianglin.entity.ProductFeedback;
import org.company.nianglin.entity.SysUser;
import org.company.nianglin.exception.BusinessException;
import org.company.nianglin.mapper.ProductFeedbackMapper;
import org.company.nianglin.mapper.SysUserMapper;
import org.company.nianglin.security.LoginUser;
import org.company.nianglin.security.SecurityUtils;
import org.company.nianglin.service.FeedbackService;
import org.company.nianglin.service.MessageService;
import org.company.nianglin.service.support.GovernanceTimeoutPolicy;
import org.company.nianglin.service.support.OperLogRecorder;
import org.company.nianglin.service.support.UserNameResolver;
import org.company.nianglin.util.ComplianceCheckUtil;
import org.company.nianglin.util.SensitiveWordUtil;
import org.company.nianglin.vo.FeedbackCreateResultVO;
import org.company.nianglin.vo.FeedbackHandleResultVO;
import org.company.nianglin.vo.FeedbackVO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 产品反馈服务实现（V6）。
 *
 * <p>对应 {@code docs/api/15-product-feedback.md}。这里刻意<b>不</b>复用
 * {@code ComplaintServiceImpl} 的任何私有方法：投诉是「A 就某笔订单投诉 B」，
 * 有双方、有归属校验、有违规计数；产品反馈三者都没有。硬套会让两边都变形。</p>
 *
 * <h3>三处口径需要特别说明（都被测试覆盖）</h3>
 *
 * <ol>
 *   <li><b>入参宽松、出参严格</b>：用户正文只过 {@link SensitiveWordUtil}；
 *       管理员处理说明过敏感词 <b>+</b> {@link ComplianceCheckUtil} 医疗红线词表 ——
 *       因为后者会经站内信原样推给用户，属于「平台产的文案」。</li>
 *   <li><b>处理说明每次流转都写，但只在终态下发</b>：中间态那段是工作草稿，
 *       渲染给用户会让人以为建议已有结论（与 {@code ComplaintVO} 同款）。</li>
 *   <li><b>只有终态发站内信</b>：`PROCESSING` 也发的话，用户会收到一条
 *       「已处理完成」，而实际处理还在进行。</li>
 * </ol>
 *
 * @author 银龄伴诊团队
 * @since V6 · 产品反馈
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FeedbackServiceImpl implements FeedbackService {

    /** 截图数量上限（与 {@code docs/api/15-product-feedback.md} §1 一致） */
    private static final int MAX_IMAGES = 3;

    /** 正文字数下限（trim 后判定，见 {@link #create}） */
    private static final int MIN_CONTENT_LENGTH = 10;

    /** 正文字数上限（trim 后判定，见 {@link #create}） */
    private static final int MAX_CONTENT_LENGTH = 1000;

    /** 处理说明字数下限（trim 后判定，见 {@link #normalizeHandleResult}） */
    private static final int MIN_HANDLE_RESULT_LENGTH = 10;

    /** 处理说明字数上限（trim 后判定，见 {@link #normalizeHandleResult}） */
    private static final int MAX_HANDLE_RESULT_LENGTH = 500;

    /**
     * 站内信正文里处理说明的摘要长度。
     *
     * <p>截断放在调用方而不是 {@code MessageTemplateUtil}：模板只负责拼接，
     * 二次截断会让「100 字」这个口径散在两处（与 {@code REVIEW_REPLIED}
     * 的 {@code replyDigest} 同款约定）。</p>
     */
    private static final int HANDLE_RESULT_DIGEST_MAX = 100;

    private final ProductFeedbackMapper feedbackMapper;
    private final SysUserMapper sysUserMapper;
    private final MessageService messageService;
    private final UserNameResolver userNameResolver;
    private final OperLogRecorder operLogRecorder;
    private final ObjectMapper objectMapper;

    /**
     * 反馈「处理中超时」阈值（天，收口 A · P1-6 反馈链）。
     *
     * <p>用 {@link Integer}：纯 Mockito 单测不起容器时为 {@code null}，
     * 按默认阈值 7 处理（与 {@code GovernanceTimeoutServiceImpl} 的默认一致）。</p>
     */
    @Value("${nianglin.feedback.processing-timeout-days:7}")
    private Integer feedbackTimeoutDays;

    /* ================================================================== */
    /* 1. 提交反馈                                                         */
    /* ================================================================== */

    @Override
    @Transactional(rollbackFor = Exception.class)
    public FeedbackCreateResultVO create(FeedbackCreateDTO dto) {
        LoginUser me = SecurityUtils.currentUser();
        FeedbackType type = FeedbackType.of(dto.getType());
        if (type == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR,
                    "反馈分类取值不合法：" + dto.getType() + "，允许取值：" + FeedbackType.allowedValues());
        }

        String content = dto.getContent() == null ? "" : dto.getContent().trim();
        // 入参只过敏感词：用户写「希望用药页能提醒我别吃错」是正当建议，
        // 套 ComplianceCheckUtil 的医疗词表会被「用药」这类词误杀。
        String hit = SensitiveWordUtil.firstHit(content);
        if (hit != null) {
            log.info("产品反馈命中敏感词，已拒绝 | submitterId={} | hit={}", me.userId(), hit);
            throw new BusinessException(ResultCode.CONTENT_SENSITIVE, "反馈内容包含敏感词：" + hit);
        }
        // trim 之后再判长度：DTO 上的 @Size 按**原始串**计数，
        // 「10 个空格 + 1 个字」能过 @Size(min=10)，trim 完却只剩 1 个字 ——
        // 那样入库就是一条「1 字反馈」，与 §1 的「10–1000 字符（trim 后）」口径不符。
        // 放在词表之后：命中敏感词时先报 6003（用户真正要改的是措辞，不是长度）。
        if (content.length() < MIN_CONTENT_LENGTH || content.length() > MAX_CONTENT_LENGTH) {
            throw new BusinessException(ResultCode.PARAM_ERROR,
                    "反馈内容长度应为 " + MIN_CONTENT_LENGTH + "–" + MAX_CONTENT_LENGTH + " 个字符");
        }

        List<String> images = normalizeImages(dto.getImages());

        ProductFeedback feedback = new ProductFeedback();
        // 提交人与角色取自登录态，不接受前端传入 —— 允许前端传「我是谁」
        // 就等于允许替别人提交，而这条通道的价值建立在「谁提的」可追溯上
        feedback.setSubmitterId(me.userId());
        feedback.setSubmitterRole(me.role());
        feedback.setType(type.name());
        feedback.setContent(content);
        feedback.setImages(writeImages(images));
        feedback.setStatus(FeedbackStatus.PENDING.name());
        feedbackMapper.insert(feedback);

        // 刻意不给管理员发站内信：反馈低频、非紧急、非当事人纠纷，
        // 塞进管理员站内信只会制造噪音（管理员有侧栏入口与专门页面）。
        log.info("产品反馈已提交 | feedbackId={} | submitterId={} | role={} | type={} | images={}",
                feedback.getId(), me.userId(), me.role(), type.name(), images.size());
        return FeedbackCreateResultVO.of(feedback.getId(), FeedbackStatus.PENDING);
    }

    /* ================================================================== */
    /* 2. 我的反馈列表                                                     */
    /* ================================================================== */

    @Override
    public PageResult<FeedbackVO> myList(FeedbackQuery query) {
        LoginUser me = SecurityUtils.currentUser();

        LambdaQueryWrapper<ProductFeedback> wrapper = Wrappers.<ProductFeedback>lambdaQuery()
                // 可见范围在 SQL 层就收窄：本 DTO 没有 submitterId 参数，无处可绕
                .eq(ProductFeedback::getSubmitterId, me.userId());
        applyStatusFilter(wrapper, query.getStatus());
        wrapper.orderByDesc(ProductFeedback::getCreateTime).orderByDesc(ProductFeedback::getId);

        var page = feedbackMapper.selectPage(query.toMpPage(), wrapper);
        return PageResult.of(page, toVOList(page.getRecords()));
    }

    /* ================================================================== */
    /* 3. 反馈详情                                                         */
    /* ================================================================== */

    @Override
    public FeedbackVO detail(Long id) {
        ProductFeedback feedback = feedbackMapper.selectById(id);
        LoginUser me = SecurityUtils.currentUser();
        boolean visible = feedback != null
                && (me.isAdmin() || me.userId().equals(feedback.getSubmitterId()));
        if (!visible) {
            // 「不存在」与「不属于你」刻意同码：若区分，等于把「这个 ID 上真的有一条
            // 别人的反馈」告诉了一个本来无权知道的人 —— 可以拿来按 ID 枚举。
            throw new BusinessException(ResultCode.FEEDBACK_NOT_FOUND);
        }
        return toVO(feedback, userNameResolver.resolveAll(List.of(feedback.getSubmitterId())));
    }

    /* ================================================================== */
    /* 4. 管理端反馈列表                                                   */
    /* ================================================================== */

    @Override
    public PageResult<FeedbackVO> adminList(AdminFeedbackQuery query) {
        LambdaQueryWrapper<ProductFeedback> wrapper = Wrappers.lambdaQuery();
        applyStatusFilter(wrapper, query.getStatus());

        if (StringUtils.hasText(query.getType())) {
            FeedbackType type = FeedbackType.of(query.getType());
            if (type == null) {
                throw new BusinessException(ResultCode.PARAM_ERROR,
                        "反馈分类取值不合法：" + query.getType() + "，允许取值：" + FeedbackType.allowedValues());
            }
            wrapper.eq(ProductFeedback::getType, type.name());
        }
        if (StringUtils.hasText(query.getKeyword())) {
            String keyword = query.getKeyword().trim();
            // 关键词要同时命中「正文」与「提交人姓名」，而姓名在 sys_user 里。
            // 先按姓名模糊查一批 id 再 IN —— 与 AdminServiceImpl#complaintList 同一手法，
            // 走 idx_submitter。姓名查不到候选时只搜正文，不加一个恒假的 IN ()。
            Set<Long> candidateIds = resolveUserIdsByKeyword(keyword);
            wrapper.and(w -> {
                w.like(ProductFeedback::getContent, keyword);
                if (!candidateIds.isEmpty()) {
                    w.or().in(ProductFeedback::getSubmitterId, candidateIds);
                }
            });
        }
        applyRange(wrapper, query.getStartDate(), query.getEndDate());
        // 待处理优先：管理端第一屏应该是待办，而不是最新提交的。
        //
        // ⚠️ 整段 ORDER BY 走 last()：MyBatis-Plus 的 orderBy* 只接受列，塞不进
        // FIELD(...)；而「既 orderBy* 又 last」会产出两个 ORDER BY 的非法 SQL
        // （与 AdminServiceImpl#FULFILLMENT_RISK_LIST_ORDER_BY 同一手法）。
        //
        // ⚠️ 为什么不用 orderByAsc(status)：status 是 VARCHAR，字典序是
        // PENDING → PROCESSING → REJECTED → RESOLVED，「未采纳」会排在「已采纳」前面，
        // 与 docs/api/15-product-feedback.md §4 承诺的「status 排序权重」相反。
        // 参数顺序从 FeedbackStatus.fieldOrder() 取，权重只在枚举里写一份。
        wrapper.last("ORDER BY FIELD(status," + FeedbackStatus.fieldOrder() + "), create_time DESC, id DESC");

        var page = feedbackMapper.selectPage(query.toMpPage(), wrapper);
        List<FeedbackVO> records = toVOList(page.getRecords());
        applyOverdueMarks(records, page.getRecords());
        return PageResult.of(page, records);
    }

    /**
     * 管理端列表的超时标记（收口 A · P1-6 反馈链）：只升级可见性，不改状态。
     *
     * <p>口径与投诉链同一份策略（{@link GovernanceTimeoutPolicy}）：{@code PROCESSING}
     * 且「上次推进时间（{@code update_time}，从未推进用 {@code create_time} 兜底）」
     * 距今满阈值天数才算超时。阈值独立于投诉链（默认 7 天）—— 反馈是建议通道
     * 不是纠纷通道，容忍度理应更高。</p>
     *
     * <p>聚合计算、不加数据库列：超时是「列表此刻的样子」，不是一笔需要审计的事实；
     * 落列反而会把「未处理」伪装成「刚处理过」（扫描器也不改 {@code update_time}）。</p>
     */
    private void applyOverdueMarks(List<FeedbackVO> records, List<ProductFeedback> rows) {
        if (records.isEmpty()) {
            return;
        }
        int threshold = feedbackTimeoutDays == null
                ? GovernanceTimeoutPolicy.DEFAULT_FEEDBACK_TIMEOUT_DAYS
                : feedbackTimeoutDays;
        LocalDateTime now = LocalDateTime.now();
        for (int i = 0; i < records.size(); i++) {
            ProductFeedback row = rows.get(i);
            Integer overdueDays = GovernanceTimeoutPolicy.overdueDays(row.getStatus(),
                    row.getUpdateTime(), row.getCreateTime(), now, threshold);
            // 与 ComplaintVO 同一口径：overdue 恒有值（true / false），overdueDays 仅超时时返回
            records.get(i).setOverdue(overdueDays != null).setOverdueDays(overdueDays);
        }
    }

    /* ================================================================== */
    /* 5. 处理反馈                                                         */
    /* ================================================================== */

    @Override
    @Transactional(rollbackFor = Exception.class)
    public FeedbackHandleResultVO handle(Long id, FeedbackHandleDTO dto) {
        ProductFeedback feedback = feedbackMapper.selectById(id);
        if (feedback == null) {
            throw new BusinessException(ResultCode.FEEDBACK_NOT_FOUND);
        }
        FeedbackStatus target = FeedbackStatus.of(dto.getStatus());
        if (target == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "反馈状态取值不合法：" + dto.getStatus());
        }
        FeedbackStatus current = FeedbackStatus.of(feedback.getStatus());
        if (current == null) {
            throw new BusinessException(ResultCode.CONFLICT, "反馈状态数据异常，请联系开发处理");
        }
        if (current == target) {
            throw new BusinessException(ResultCode.CONFLICT, "反馈已处于该状态");
        }
        if (!current.canTransitTo(target)) {
            // 状态机只可正向流转；回退（已采纳改回处理中）与跳级（待处理直接已采纳）一律 409
            throw new BusinessException(ResultCode.CONFLICT,
                    "反馈状态不能从「" + current.getLabel() + "」变更为「" + target.getLabel() + "」");
        }

        String handleResult = normalizeHandleResult(dto.getHandleResult());

        LoginUser me = SecurityUtils.currentUser();
        LocalDateTime now = LocalDateTime.now();

        // 条件更新：WHERE id = ? AND status = <读到的旧状态>。
        // 并发下第二个管理员的影响行数为 0，按 409 处理 —— 与 handleComplaint 同一手法。
        int affected = feedbackMapper.update(null, Wrappers.<ProductFeedback>lambdaUpdate()
                .eq(ProductFeedback::getId, id)
                .eq(ProductFeedback::getStatus, current.name())
                .set(ProductFeedback::getStatus, target.name())
                .set(ProductFeedback::getHandleResult, handleResult)
                .set(ProductFeedback::getHandleAdminId, me.userId())
                .set(ProductFeedback::getHandleTime, now));
        if (affected == 0) {
            throw new BusinessException(ResultCode.CONFLICT, "该反馈刚刚被其他管理员处理，请刷新后重试");
        }

        if (target.isTerminal()) {
            // 只在终态通知提交人：中间态也发的话，用户会收到一条「已处理完成」，
            // 而实际处理还在进行（投诉侧同款口径）
            Map<String, Object> params = new HashMap<>();
            params.put("handleResult", digest(handleResult));
            messageService.send(feedback.getSubmitterId(), MessageType.FEEDBACK_HANDLED, id, params);
        }

        operLogRecorder.record(OperType.HANDLE_FEEDBACK, OperTargetType.FEEDBACK, id,
                "反馈 #" + id, current.name(), target.name(), handleResult);

        log.info("产品反馈已处理 | feedbackId={} | {} → {} | adminId={}",
                id, current.name(), target.name(), me.userId());
        return FeedbackHandleResultVO.of(id, target, now);
    }

    /* ================================================================== */
    /* 内部：校验与文本                                                    */
    /* ================================================================== */

    /**
     * 处理说明：过敏感词 <b>+</b> 医疗红线词表。
     *
     * <p>为什么要比入参多一道：这段话会经 {@code FEEDBACK_HANDLED} 站内信
     * <b>原样推给用户</b>，属于平台产出的文案。管理员写下「建议您调整剂量」
     * 就是平台在给用药建议 —— 红线-1 不因为「回复里说的」而豁免。</p>
     */
    private static String normalizeHandleResult(String raw) {
        String text = raw == null ? "" : raw.trim();
        String sensitiveHit = SensitiveWordUtil.firstHit(text);
        if (sensitiveHit != null) {
            throw new BusinessException(ResultCode.CONTENT_SENSITIVE,
                    "处理说明包含敏感词（" + sensitiveHit + "），请修改后重试");
        }
        String complianceHit = ComplianceCheckUtil.firstHit(text);
        if (complianceHit != null) {
            throw new BusinessException(ResultCode.CONTENT_SENSITIVE,
                    "处理说明包含医疗建议类表述（" + complianceHit + "），请修改后重试");
        }
        // 长度放在两套词表之后：与正文同理，@Size 按原始串计数，
        // trim 后可能掉到下限以下（「10 个空格 + 1 个字」过得了 @Size(min=10)），
        // 而库里这一列的约定是「10-500 字」。命中词表时先报 6003 —— 那是管理员真正要改的。
        if (text.length() < MIN_HANDLE_RESULT_LENGTH || text.length() > MAX_HANDLE_RESULT_LENGTH) {
            throw new BusinessException(ResultCode.PARAM_ERROR,
                    "处理说明长度应为 " + MIN_HANDLE_RESULT_LENGTH + "–" + MAX_HANDLE_RESULT_LENGTH + " 个字符");
        }
        return text;
    }

    /** 站内信正文里的摘要：超长截断并加省略号，模板不再二次截断 */
    private static String digest(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= HANDLE_RESULT_DIGEST_MAX
                ? text
                : text.substring(0, HANDLE_RESULT_DIGEST_MAX) + "…";
    }

    /** 状态筛选：非法取值直接 400，不静默忽略（否则前端以为筛选生效了） */
    private static void applyStatusFilter(LambdaQueryWrapper<ProductFeedback> wrapper, String raw) {
        if (!StringUtils.hasText(raw)) {
            return;
        }
        FeedbackStatus status = FeedbackStatus.of(raw);
        if (status == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "反馈状态取值不合法：" + raw);
        }
        wrapper.eq(ProductFeedback::getStatus, status.name());
    }

    /**
     * 通用日期区间条件：{@code [startDate 00:00, endDate 23:59:59]}。
     *
     * <p>结束日用 {@code le 23:59:59} 而不是 {@code lt 次日 00:00}：两种写法等价，
     * 但前者与「含当天」这句业务描述长得更像（与 {@code AdminServiceImpl#applyRange} 同口径）。</p>
     */
    private static void applyRange(LambdaQueryWrapper<ProductFeedback> wrapper,
                                   String startDate, String endDate) {
        LocalDate start = parseDate(startDate, "提交时间起");
        LocalDate end = parseDate(endDate, "提交时间止");
        if (start != null && end != null && start.isAfter(end)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "提交时间区间的开始日期不能晚于结束日期");
        }
        if (start != null) {
            wrapper.ge(ProductFeedback::getCreateTime, start.atStartOfDay());
        }
        if (end != null) {
            wrapper.le(ProductFeedback::getCreateTime, end.atTime(23, 59, 59));
        }
    }

    private static LocalDate parseDate(String value, String field) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw new BusinessException(ResultCode.PARAM_ERROR, field + " 日期格式应为 yyyy-MM-dd");
        }
    }

    /** 关键词 → 用户 ID 候选集（姓名 / 昵称 / 用户名都算） */
    private Set<Long> resolveUserIdsByKeyword(String keyword) {
        List<SysUser> candidates = sysUserMapper.selectList(Wrappers.<SysUser>lambdaQuery()
                .select(SysUser::getId)
                .and(w -> w.like(SysUser::getRealName, keyword)
                        .or().like(SysUser::getNickname, keyword)
                        .or().like(SysUser::getUsername, keyword)));
        Set<Long> ids = new LinkedHashSet<>(candidates.size());
        for (SysUser user : candidates) {
            ids.add(user.getId());
        }
        return ids;
    }

    /* ================================================================== */
    /* 内部：转换                                                          */
    /* ================================================================== */

    private List<FeedbackVO> toVOList(List<ProductFeedback> rows) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        Set<Long> userIds = new LinkedHashSet<>();
        for (ProductFeedback row : rows) {
            userIds.add(row.getSubmitterId());
        }
        Map<Long, String> names = userNameResolver.resolveAll(userIds);

        List<FeedbackVO> records = new ArrayList<>(rows.size());
        for (ProductFeedback row : rows) {
            records.add(FeedbackVO.of(row,
                    names.get(row.getSubmitterId()),
                    parseImages(row.getImages())));
        }
        return records;
    }

    private FeedbackVO toVO(ProductFeedback row, Map<Long, String> names) {
        return FeedbackVO.of(row, names.get(row.getSubmitterId()), parseImages(row.getImages()));
    }

    /** 截图 URL 去重、截断；空白项直接丢掉（前端多传一个空串很常见） */
    private static List<String> normalizeImages(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        Set<String> distinct = new LinkedHashSet<>();
        for (String url : raw) {
            if (!StringUtils.hasText(url)) {
                continue;
            }
            distinct.add(url.trim());
            if (distinct.size() >= MAX_IMAGES) {
                break;
            }
        }
        return new ArrayList<>(distinct);
    }

    private String writeImages(List<String> images) {
        if (images == null || images.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(images);
        } catch (Exception e) {
            log.error("产品反馈截图序列化失败 | {}", e.getMessage());
            throw new BusinessException(ResultCode.SYSTEM_ERROR, "保存截图失败，请稍后重试");
        }
    }

    /** 解析截图 JSON；内容损坏时按空处理，不让一条脏数据把列表打挂 */
    private List<String> parseImages(String json) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            List<String> parsed = objectMapper.readValue(json, new TypeReference<List<String>>() {
            });
            return parsed == null ? List.of() : parsed;
        } catch (Exception e) {
            log.warn("产品反馈截图 JSON 解析失败，已按空处理 | len={} | {}", json.length(), e.getMessage());
            return List.of();
        }
    }
}

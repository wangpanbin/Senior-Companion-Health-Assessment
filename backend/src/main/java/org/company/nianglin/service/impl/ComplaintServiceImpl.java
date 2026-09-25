package org.company.nianglin.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.company.nianglin.common.PageResult;
import org.company.nianglin.common.ResultCode;
import org.company.nianglin.constant.ComplaintStatus;
import org.company.nianglin.constant.ComplaintType;
import org.company.nianglin.constant.MessageType;
import org.company.nianglin.constant.RoleConstants;
import org.company.nianglin.dto.ComplaintCreateDTO;
import org.company.nianglin.dto.ComplaintQuery;
import org.company.nianglin.entity.CompanionOrder;
import org.company.nianglin.entity.Complaint;
import org.company.nianglin.entity.OrderReview;
import org.company.nianglin.entity.SysUser;
import org.company.nianglin.exception.BusinessException;
import org.company.nianglin.mapper.ComplaintMapper;
import org.company.nianglin.mapper.OrderReviewMapper;
import org.company.nianglin.mapper.SysUserMapper;
import org.company.nianglin.security.LoginUser;
import org.company.nianglin.security.SecurityUtils;
import org.company.nianglin.service.ComplaintService;
import org.company.nianglin.service.MessageService;
import org.company.nianglin.service.OrderService;
import org.company.nianglin.service.support.UserNameResolver;
import org.company.nianglin.util.MaskUtil;
import org.company.nianglin.util.SensitiveWordUtil;
import org.company.nianglin.vo.ComplaintCreateResultVO;
import org.company.nianglin.vo.ComplaintVO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 投诉服务实现。
 *
 * @author 银龄伴诊团队
 * @since M7
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ComplaintServiceImpl implements ComplaintService {

    /** 证据材料数量上限（与 {@code docs/api/06-review-complaint.md} §5 一致） */
    private static final int MAX_EVIDENCE = 6;

    /** 视角参数取值：我投诉的 */
    private static final String ROLE_AS_COMPLAINANT = "AS_COMPLAINANT";

    /** 视角参数取值：投诉我的 */
    private static final String ROLE_AS_TARGET = "AS_TARGET";

    private final ComplaintMapper complaintMapper;
    private final SysUserMapper sysUserMapper;
    private final OrderService orderService;
    private final MessageService messageService;
    private final UserNameResolver userNameResolver;
    private final ObjectMapper objectMapper;

    /**
     * 评价表读 mapper（E4 评价申诉专用）。
     *
     * <p>复用 {@code OrderReviewMapper}（V1 已建，零迁移），不引入新表 —— 申诉
     * 通过 {@code (reviewId → orderId → complaint.orderId + type=REVIEW_APPEAL)} 三段反查
     * 完成关联，详见 {@code ComplaintMapper.countOpenAppealByReviewId} 的注释。</p>
     */
    private final OrderReviewMapper orderReviewMapper;

    /**
     * 评价申诉时限（E4）。
     *
     * <p>评价提交后多少日内可发起申诉；超时由本类的「REVIEW_APPEAL 分支」
     * 返回 409。阈值落 {@code application.yml} 的 {@code nianglin.review.appeal-deadline-days}
     * （默认 15），不在代码里硬编码 —— 与项目既有「业务阈值走配置」的惯例一致。</p>
     */
    @Value("${nianglin.review.appeal-deadline-days:15}")
    private int appealDeadlineDays;

    /* ================================================================== */
    /* 5. 提交投诉                                                         */
    /* ================================================================== */

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ComplaintCreateResultVO create(ComplaintCreateDTO dto) {
        LoginUser me = SecurityUtils.currentUser();

        // 归属校验复用订单模块：3001 / 3004 的判定口径与订单详情完全一致
        CompanionOrder order = orderService.requireInvolved(dto.getOrderId());
        ComplaintType type = ComplaintType.of(dto.getType());
        if (type == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "投诉类型取值不合法：" + dto.getType());
        }

        long complainantId;
        String complainantRole;
        Long targetUserId;
        String targetRole;
        if (me.userId().equals(order.getFamilyId())) {
            complainantId = me.userId();
            complainantRole = RoleConstants.FAMILY;
            targetUserId = order.getCompanionId();
            targetRole = RoleConstants.COMPANION;
        } else if (order.getCompanionId() != null && me.userId().equals(order.getCompanionId())) {
            complainantId = me.userId();
            complainantRole = RoleConstants.COMPANION;
            targetUserId = order.getFamilyId();
            targetRole = RoleConstants.FAMILY;
        } else {
            // ADMIN 也走这里。管理员处理投诉走 M9 的处置接口（会写 admin_oper_log），
            // 不能以当事人身份发起 —— 那等于开了一条不留痕的栽赃通道
            throw new BusinessException(ResultCode.ORDER_NO_PERMISSION);
        }
        if (targetUserId == null) {
            // 家属在「还没人接单」时投诉：此时订单里根本不存在服务方，
            // 硬写一条 target_user_id 为空的投诉会让管理员无从下手
            throw new BusinessException(ResultCode.CONFLICT, "订单尚未被接单，暂无被投诉对象");
        }

        // 评价申诉（E4）专属校验：归属三方一致 + 时限 + 同评价未结案唯一。
        // 必须排在「订单未结案投诉」之前：若已有同订单未结案投诉（普通投诉或别的申诉），
        // 应当走那条共用的「已有未结案」409，本分支的同评价唯一约束只针对同 reviewId 增量检查。
        if (type == ComplaintType.REVIEW_APPEAL) {
            validateReviewAppeal(dto, order, complainantId);
        }

        Long openCount = complaintMapper.selectCount(Wrappers.<Complaint>lambdaQuery()
                .eq(Complaint::getOrderId, order.getId())
                .in(Complaint::getStatus, ComplaintStatus.PENDING.name(), ComplaintStatus.PROCESSING.name()));
        if (openCount != null && openCount > 0) {
            throw new BusinessException(ResultCode.CONFLICT, "该订单已有未处理完的投诉，请等待管理员处理");
        }

        String hit = SensitiveWordUtil.firstHit(dto.getContent());
        if (hit != null) {
            log.info("投诉命中敏感词，已拒绝 | orderId={} | hit={}", order.getId(), hit);
            throw new BusinessException(ResultCode.CONTENT_SENSITIVE, "投诉内容包含敏感词：" + hit);
        }

        Complaint complaint = new Complaint();
        complaint.setOrderId(order.getId());
        complaint.setOrderNo(order.getOrderNo());
        complaint.setComplainantId(complainantId);
        complaint.setComplainantRole(complainantRole);
        complaint.setTargetUserId(targetUserId);
        complaint.setTargetRole(targetRole);
        complaint.setType(type.name());
        complaint.setContent(dto.getContent().trim());
        complaint.setEvidence(writeEvidence(normalizeEvidence(dto.getEvidence())));
        complaint.setStatus(ComplaintStatus.PENDING.name());
        complaint.setPenaltyToTarget(0);
        complaintMapper.insert(complaint);

        notifyAdmins(complaint, type);
        notifyTarget(complaint, type);

        log.info("投诉已提交 | complaintId={} | orderId={} | type={} | complainant={} | target={}",
                complaint.getId(), order.getId(), type.name(), complainantId, targetUserId);
        return ComplaintCreateResultVO.of(complaint.getId(), ComplaintStatus.PENDING);
    }

    /* ================================================================== */
    /* 6. 我的投诉列表                                                     */
    /* ================================================================== */

    @Override
    public PageResult<ComplaintVO> myList(ComplaintQuery query) {
        LoginUser me = SecurityUtils.currentUser();
        Long meId = me.userId();

        var wrapper = Wrappers.<Complaint>lambdaQuery();
        // 可见范围在 SQL 层就收窄：无论 role 参数怎么传，都不可能看到无关投诉
        if (ROLE_AS_COMPLAINANT.equalsIgnoreCase(query.getRole())) {
            wrapper.eq(Complaint::getComplainantId, meId);
        } else if (ROLE_AS_TARGET.equalsIgnoreCase(query.getRole())) {
            wrapper.eq(Complaint::getTargetUserId, meId);
        } else {
            wrapper.and(w -> w.eq(Complaint::getComplainantId, meId)
                    .or()
                    .eq(Complaint::getTargetUserId, meId));
        }

        if (StringUtils.hasText(query.getStatus())) {
            ComplaintStatus status = ComplaintStatus.of(query.getStatus());
            if (status == null) {
                throw new BusinessException(ResultCode.PARAM_ERROR, "投诉状态取值不合法：" + query.getStatus());
            }
            wrapper.eq(Complaint::getStatus, status.name());
        }
        wrapper.orderByDesc(Complaint::getCreateTime).orderByDesc(Complaint::getId);

        var page = complaintMapper.selectPage(query.toMpPage(), wrapper);

        Set<Long> userIds = new LinkedHashSet<>();
        for (Complaint complaint : page.getRecords()) {
            userIds.add(complaint.getComplainantId());
            userIds.add(complaint.getTargetUserId());
        }
        Map<Long, String> names = userNameResolver.resolveAll(userIds);

        List<ComplaintVO> records = new ArrayList<>(page.getRecords().size());
        for (Complaint complaint : page.getRecords()) {
            records.add(toVO(complaint, names));
        }
        return PageResult.of(page, records);
    }

    /* ================================================================== */
    /* 7. 投诉详情                                                         */
    /* ================================================================== */

    @Override
    public ComplaintVO detail(Long id) {
        Complaint complaint = complaintMapper.selectById(id);
        if (complaint == null) {
            throw new BusinessException(ResultCode.COMPLAINT_NOT_FOUND);
        }
        LoginUser me = SecurityUtils.currentUser();
        boolean involved = me.isAdmin()
                || me.userId().equals(complaint.getComplainantId())
                || me.userId().equals(complaint.getTargetUserId());
        if (!involved) {
            // 越权与不存在的错误码必须区分：都回 6004 会让「这条投诉真的没了」
            // 与「这条投诉不给我看」在前端表现完全一致，排查时无从下手
            throw new BusinessException(ResultCode.FORBIDDEN);
        }
        return toVO(complaint, userNameResolver.resolveAll(
                List.of(complaint.getComplainantId(), complaint.getTargetUserId())));
    }

    /* ================================================================== */
    /* 内部：评价申诉校验（E4）                                              */
    /* ================================================================== */

    /**
     * 评价申诉的归属 / 时限 / 同评价未结案唯一 三重校验。
     *
     * <p>任何一项不满足都直接抛 {@link BusinessException}，调用方不必再判断返回值。
     * 顺序刻意是「评价存在 → 评价属于本陪诊员 → 评价与订单一致 → 时限 → 同评价未结案」：
     * 越靠前的越接近「数据完整性」问题（评价不存在 / 不属于本人），越靠后越接近
     * 「业务约束」（时限 / 唯一性），前面失败时不会触发后面那条更慢的 SQL。</p>
     */
    private void validateReviewAppeal(ComplaintCreateDTO dto, CompanionOrder order, Long complainantId) {
        Long reviewId = dto.getReviewId();
        if (reviewId == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "评价申诉必须传入 reviewId");
        }

        OrderReview review = orderReviewMapper.selectById(reviewId);
        if (review == null) {
            throw new BusinessException(ResultCode.REVIEW_NOT_FOUND);
        }
        if (!Objects.equals(review.getCompanionId(), complainantId)) {
            throw new BusinessException(ResultCode.ORDER_NO_PERMISSION);
        }
        if (!Objects.equals(review.getOrderId(), order.getId())) {
            // 「评价 ↔ 订单 ↔ 申诉人」三方一致：dto.orderId 已通过 requireInvolved 校验，
            // 这里只比 review.orderId 与之相等。顺序反了就会漏掉这一类伪造
            throw new BusinessException(ResultCode.ORDER_NO_PERMISSION);
        }

        // 时限：评价提交后多少日内可申诉。createTime 是评价的提交时间，不是申诉时间；
        // 用「评价提交时间 + N 天 ≤ now」反推「是否在 N 日内」，而不是「now - 评价时间 ≤ N」
        // —— 两者数学上等价，但前者读起来更接近自然语言
        LocalDateTime deadline = review.getCreateTime().plusDays(appealDeadlineDays);
        if (deadline.isBefore(LocalDateTime.now())) {
            throw new BusinessException(ResultCode.CONFLICT,
                    "评价申诉须在评价提交后 " + appealDeadlineDays + " 日内发起");
        }

        // 同 reviewId 已有未结案（PENDING/PROCESSING）申诉 → 409（PRD §RK-01 防滥用）
        long openAppeal = complaintMapper.countOpenAppealByReviewId(reviewId);
        if (openAppeal > 0) {
            throw new BusinessException(ResultCode.CONFLICT, "该评价的申诉正在处理中");
        }
    }

    /* ================================================================== */
    /* 内部：通知（M8）                                                    */
    /* ================================================================== */

    /**
     * 通知全部管理员。
     *
     * <p>投诉是「必须被人看到」的业务：只写数据库不推消息，投诉会一直躺在后台列表里
     * 等某个管理员想起来去翻。管理员账号在系统里数量极少（一期就几个），
     * 群发的成本可以忽略。</p>
     */
    private void notifyAdmins(Complaint complaint, ComplaintType type) {
        List<Long> adminIds = sysUserMapper.selectList(Wrappers.<SysUser>lambdaQuery()
                        .eq(SysUser::getRole, RoleConstants.ADMIN)
                        .select(SysUser::getId))
                .stream().map(SysUser::getId).toList();
        if (adminIds.isEmpty()) {
            log.warn("系统中没有管理员账号，投诉站内信未发送 | complaintId={}", complaint.getId());
            return;
        }
        Map<String, Object> params = new HashMap<>();
        params.put("submitterName", MaskUtil.name(userNameResolver.resolve(complaint.getComplainantId())));
        params.put("orderNo", complaint.getOrderNo());
        params.put("typeLabel", type.getLabel());
        messageService.sendBatch(adminIds, MessageType.COMPLAINT_SUBMITTED, complaint.getId(), params);
    }

    /** 告知被投诉方：不告知会让对方在毫无准备的情况下被叫去「说明情况」 */
    private void notifyTarget(Complaint complaint, ComplaintType type) {
        Map<String, Object> params = new HashMap<>();
        params.put("submitterName", MaskUtil.name(userNameResolver.resolve(complaint.getComplainantId())));
        params.put("orderNo", complaint.getOrderNo());
        params.put("typeLabel", type.getLabel());
        messageService.send(complaint.getTargetUserId(), MessageType.COMPLAINT_SUBMITTED,
                complaint.getId(), params);
    }

    /* ================================================================== */
    /* 内部：转换                                                          */
    /* ================================================================== */

    private ComplaintVO toVO(Complaint complaint, Map<Long, String> names) {
        return ComplaintVO.of(complaint,
                names.get(complaint.getComplainantId()),
                names.get(complaint.getTargetUserId()),
                parseEvidence(complaint.getEvidence()));
    }

    /** 证据 URL 去重、截断；空白项直接丢掉（前端多传一个空串很常见） */
    private List<String> normalizeEvidence(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        Set<String> distinct = new LinkedHashSet<>();
        for (String url : raw) {
            if (!StringUtils.hasText(url)) {
                continue;
            }
            distinct.add(url.trim());
            if (distinct.size() >= MAX_EVIDENCE) {
                break;
            }
        }
        return new ArrayList<>(distinct);
    }

    private String writeEvidence(List<String> evidence) {
        if (evidence == null || evidence.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(evidence);
        } catch (Exception e) {
            log.error("投诉证据序列化失败 | {}", e.getMessage());
            throw new BusinessException(ResultCode.SYSTEM_ERROR, "保存证据材料失败，请稍后重试");
        }
    }

    /** 解析证据 JSON；内容损坏时按空处理，不让一条脏数据把详情页打挂 */
    private List<String> parseEvidence(String json) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            List<String> parsed = objectMapper.readValue(json, new TypeReference<List<String>>() {
            });
            return parsed == null ? List.of() : parsed;
        } catch (Exception e) {
            log.warn("投诉证据 JSON 解析失败，已按空处理 | len={} | {}", json.length(), e.getMessage());
            return List.of();
        }
    }
}

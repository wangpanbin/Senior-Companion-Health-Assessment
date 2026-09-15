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
import org.company.nianglin.entity.SysUser;
import org.company.nianglin.exception.BusinessException;
import org.company.nianglin.mapper.ComplaintMapper;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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

package org.company.nianglin.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.company.nianglin.common.PageResult;
import org.company.nianglin.common.ResultCode;
import org.company.nianglin.constant.AccountStatus;
import org.company.nianglin.constant.AuditStatus;
import org.company.nianglin.constant.ComplaintStatus;
import org.company.nianglin.constant.MessageType;
import org.company.nianglin.constant.OperTargetType;
import org.company.nianglin.constant.OperType;
import org.company.nianglin.constant.OrderStatus;
import org.company.nianglin.constant.RoleConstants;
import org.company.nianglin.constant.WorkStatus;
import org.company.nianglin.dto.AdminAuditQuery;
import org.company.nianglin.dto.AdminComplaintQuery;
import org.company.nianglin.dto.AdminOrderQuery;
import org.company.nianglin.dto.AdminUserQuery;
import org.company.nianglin.dto.ArbitrateDTO;
import org.company.nianglin.dto.AuditDecisionDTO;
import org.company.nianglin.dto.CertificateItem;
import org.company.nianglin.dto.ComplaintHandleDTO;
import org.company.nianglin.dto.OperLogQuery;
import org.company.nianglin.dto.ResetPasswordDTO;
import org.company.nianglin.dto.UserDisableDTO;
import org.company.nianglin.dto.UserEnableDTO;
import org.company.nianglin.entity.AdminOperLog;
import org.company.nianglin.entity.CompanionAuditRecord;
import org.company.nianglin.entity.CompanionOrder;
import org.company.nianglin.entity.CompanionProfile;
import org.company.nianglin.entity.Complaint;
import org.company.nianglin.entity.ElderProfile;
import org.company.nianglin.entity.SysUser;
import org.company.nianglin.exception.BusinessException;
import org.company.nianglin.mapper.AdminOperLogMapper;
import org.company.nianglin.mapper.AdminReadMapper;
import org.company.nianglin.mapper.CompanionAuditRecordMapper;
import org.company.nianglin.mapper.CompanionOrderMapper;
import org.company.nianglin.mapper.CompanionProfileMapper;
import org.company.nianglin.mapper.ComplaintMapper;
import org.company.nianglin.mapper.OrderReadMapper;
import org.company.nianglin.mapper.SysUserMapper;
import org.company.nianglin.security.LoginUser;
import org.company.nianglin.security.SecurityProperties;
import org.company.nianglin.security.SecurityUtils;
import org.company.nianglin.security.TokenStore;
import org.company.nianglin.service.AdminService;
import org.company.nianglin.service.MessageService;
import org.company.nianglin.service.OrderService;
import org.company.nianglin.service.support.UserNameResolver;
import org.company.nianglin.util.AesUtil;
import org.company.nianglin.util.MaskUtil;
import org.company.nianglin.util.RequestInfoUtil;
import org.company.nianglin.vo.AdminOrderVO;
import org.company.nianglin.vo.AdminUserVO;
import org.company.nianglin.vo.ArbitrateResultVO;
import org.company.nianglin.vo.AuditApplicationVO;
import org.company.nianglin.vo.AuditDecisionResultVO;
import org.company.nianglin.vo.AuditPageVO;
import org.company.nianglin.vo.ComplaintHandleResultVO;
import org.company.nianglin.vo.ComplaintVO;
import org.company.nianglin.vo.OperLogVO;
import org.company.nianglin.vo.OrderVO;
import org.company.nianglin.vo.ResetPasswordResultVO;
import org.company.nianglin.vo.UserStatusResultVO;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Period;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 管理后台服务实现。
 *
 * @author 银龄伴诊团队
 * @since M9
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminServiceImpl implements AdminService {

    /**
     * 管理员重置密码后的默认密码。
     *
     * <p>固定值而不是随机生成：这个密码要走电话念给用户（实际场景就是
     * 「老人家属来电说密码忘了」），随机的 16 位串在电话里必然念错。
     * 安全性由「首次登录强制修改」来保证（{@code need_change_password = 1}），
     * 而不是由密码本身的复杂度。</p>
     */
    private static final String DEFAULT_PASSWORD = "Nl@123456";

    /** 驳回原因的最小长度（文档 §3：5–200 字符） */
    private static final int MIN_REJECT_REASON_LENGTH = 5;

    /** {@code admin_oper_log.target_desc} 的列宽，超长会直接抛「Data too long」 */
    private static final int MAX_TARGET_DESC_LENGTH = 100;

    private final CompanionAuditRecordMapper auditRecordMapper;
    private final CompanionProfileMapper companionProfileMapper;
    private final SysUserMapper sysUserMapper;
    private final ComplaintMapper complaintMapper;
    private final CompanionOrderMapper orderMapper;
    private final AdminOperLogMapper operLogMapper;
    private final AdminReadMapper adminReadMapper;
    private final OrderReadMapper orderReadMapper;
    private final MessageService messageService;
    private final OrderService orderService;
    private final UserNameResolver userNameResolver;
    private final PasswordEncoder passwordEncoder;
    private final SecurityProperties securityProperties;
    private final TokenStore tokenStore;
    private final ObjectMapper objectMapper;

    /* ================================================================== */
    /* 1 / 2 / 3. 资质审核                                                 */
    /* ================================================================== */

    @Override
    public AuditPageVO auditList(AdminAuditQuery query) {
        LambdaQueryWrapper<CompanionAuditRecord> wrapper = Wrappers.lambdaQuery();
        applyAuditStatus(wrapper, query.getAuditStatus());
        applyKywordOnApplicant(wrapper, query.getKeyword());
        applySubmitTimeRange(wrapper, query.getStartDate(), query.getEndDate());
        wrapper.orderByAsc(CompanionAuditRecord::getAuditStatus)
                .orderByAsc(CompanionAuditRecord::getSubmitTime)
                .orderByAsc(CompanionAuditRecord::getId);

        Page<CompanionAuditRecord> page = auditRecordMapper.selectPage(query.toMpPage(), wrapper);

        Map<Long, SysUser> users = loadUsers(page.getRecords().stream()
                .map(CompanionAuditRecord::getApplicantUserId).toList());
        Map<Long, String> auditorNames = userNameResolver.resolveAll(page.getRecords().stream()
                .map(CompanionAuditRecord::getAuditAdminId)
                .filter(Objects::nonNull)
                .collect(LinkedHashSet::new, LinkedHashSet::add, LinkedHashSet::addAll));

        List<AuditApplicationVO> records = new ArrayList<>(page.getRecords().size());
        for (CompanionAuditRecord record : page.getRecords()) {
            SysUser applicant = users.get(record.getApplicantUserId());
            records.add(AuditApplicationVO.of(record,
                    applicant == null ? null : applicant.getUsername(),
                    MaskUtil.phone(applicant == null ? null : applicant.getPhone()),
                    maskedIdCard(record.getIdCard()),
                    parseCertificates(record.getCertificates()),
                    MaskUtil.name(auditorNames.get(record.getAuditAdminId())),
                    false));
        }

        return AuditPageVO.of(page.getTotal(), page.getCurrent(), page.getSize(), page.getPages(),
                auditStatusCounts(), records);
    }

    @Override
    public AuditApplicationVO auditDetail(Long id) {
        CompanionAuditRecord record = auditRecordMapper.selectById(id);
        if (record == null) {
            throw new BusinessException(ResultCode.COMPANION_NOT_FOUND, "资质申请不存在");
        }
        SysUser applicant = sysUserMapper.selectById(record.getApplicantUserId());
        String auditorName = record.getAuditAdminId() == null
                ? null
                : userNameResolver.resolve(record.getAuditAdminId());
        return AuditApplicationVO.of(record,
                applicant == null ? null : applicant.getUsername(),
                MaskUtil.phone(applicant == null ? null : applicant.getPhone()),
                maskedIdCard(record.getIdCard()),
                parseCertificates(record.getCertificates()),
                MaskUtil.name(auditorName),
                true);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AuditDecisionResultVO decideAudit(Long id, AuditDecisionDTO dto) {
        CompanionAuditRecord record = auditRecordMapper.selectById(id);
        if (record == null) {
            throw new BusinessException(ResultCode.COMPANION_NOT_FOUND, "资质申请不存在");
        }
        AuditStatus current = AuditStatus.of(record.getAuditStatus());
        if (current == null || current != AuditStatus.PENDING) {
            // 已通过 / 已驳回都是终态。允许再审等于允许「先驳回再偷偷改成通过」，
            // 而两次审核的时间戳与审核人都会被覆盖掉，审计链就断了
            throw new BusinessException(ResultCode.AUDIT_STATUS_ILLEGAL);
        }

        boolean approved = Boolean.TRUE.equals(dto.getApproved());
        String rejectReason = trimmed(dto.getRejectReason());
        if (!approved && (rejectReason == null || rejectReason.length() < MIN_REJECT_REASON_LENGTH)) {
            throw new BusinessException(ResultCode.AUDIT_REASON_REQUIRED);
        }

        LoginUser me = SecurityUtils.currentUser();
        LocalDateTime now = LocalDateTime.now();

        auditRecordMapper.update(null, Wrappers.<CompanionAuditRecord>lambdaUpdate()
                .eq(CompanionAuditRecord::getId, id)
                .eq(CompanionAuditRecord::getAuditStatus, AuditStatus.PENDING.name())
                .set(CompanionAuditRecord::getAuditStatus,
                        approved ? AuditStatus.APPROVED.name() : AuditStatus.REJECTED.name())
                .set(CompanionAuditRecord::getRejectReason, approved ? null : rejectReason)
                .set(CompanionAuditRecord::getAuditAdminId, me.userId())
                .set(CompanionAuditRecord::getAuditRemark, trimmed(dto.getRemark()))
                .set(CompanionAuditRecord::getAuditTime, now));

        // 快照同步：订单模块只看 companion_profile.audit_status，
        // 只改流水不改快照会让「审核已通过」与「仍然接不了单」同时成立
        companionProfileMapper.update(null, Wrappers.<CompanionProfile>lambdaUpdate()
                .eq(CompanionProfile::getUserId, record.getApplicantUserId())
                .set(CompanionProfile::getAuditStatus,
                        approved ? AuditStatus.APPROVED.name() : AuditStatus.REJECTED.name())
                .set(CompanionProfile::getRejectReason, approved ? null : rejectReason)
                .set(CompanionProfile::getAuditAdminId, me.userId())
                .set(CompanionProfile::getAuditTime, now)
                .set(approved, CompanionProfile::getWorkStatus, WorkStatus.AVAILABLE.name()));

        if (approved) {
            upgradeToCompanion(record.getApplicantUserId());
        }

        Map<String, Object> params = new HashMap<>();
        params.put("result", approved ? "已通过" : "已驳回");
        params.put("rejectReason", approved ? "" : "原因：" + rejectReason);
        messageService.send(record.getApplicantUserId(), MessageType.AUDIT_RESULT, record.getId(), params);

        writeOperLog(OperType.AUDIT_COMPANION, OperTargetType.COMPANION, record.getApplicantUserId(),
                "资质申请 #" + record.getId(), current.name(),
                approved ? AuditStatus.APPROVED.name() : AuditStatus.REJECTED.name(),
                approved ? "审核通过" : "审核驳回：" + rejectReason);

        log.info("资质审核完成 | applicationId={} | userId={} | approved={} | adminId={}",
                id, record.getApplicantUserId(), approved, me.userId());
        return AuditDecisionResultVO.of(id,
                approved ? AuditStatus.APPROVED : AuditStatus.REJECTED);
    }

    /** 审核通过后把账号角色提升为陪诊员；已经是陪诊员则不动 */
    private void upgradeToCompanion(Long userId) {
        SysUser user = sysUserMapper.selectById(userId);
        if (user == null || RoleConstants.COMPANION.equals(user.getRole())) {
            return;
        }
        sysUserMapper.update(null, Wrappers.<SysUser>lambdaUpdate()
                .eq(SysUser::getId, userId)
                .set(SysUser::getRole, RoleConstants.COMPANION));
        // 角色写在 token 里，不递增密码版本的话，申请人手里那张「FAMILY」的旧令牌
        // 会一直用到过期为止 —— 那时用户会看到「资质已通过」却怎么都进不了陪诊员页面，
        // 而所有排查都会指向「后端鉴权坏了」。递增版本让他重新登录一次，拿到新角色
        tokenStore.bumpPasswordVersion(userId);
        log.info("账号角色已升级为陪诊员（旧令牌已失效，需重新登录） | userId={}", userId);
    }

    /* ================================================================== */
    /* 4 ~ 7. 用户管理                                                     */
    /* ================================================================== */

    @Override
    public PageResult<AdminUserVO> userList(AdminUserQuery query) {
        Page<SysUser> page = sysUserMapper.selectPage(query.toMpPage(), userWrapper(query));
        List<SysUser> rows = page.getRecords();
        if (rows.isEmpty()) {
            return new PageResult<>(page.getTotal(), page.getCurrent(), page.getSize(), page.getPages(), List.of());
        }

        Map<Long, Integer> orderCounts = orderCountsOf(rows.stream().map(SysUser::getId).toList());
        List<AdminUserVO> records = new ArrayList<>(rows.size());
        for (SysUser user : rows) {
            records.add(AdminUserVO.of(user, orderCounts.get(user.getId())));
        }
        return PageResult.of(page, records);
    }

    @Override
    public List<SysUser> queryUsersForExport(AdminUserQuery query, int limit) {
        return sysUserMapper.selectList(userWrapper(query)
                // 多查一行：够用来判断「是否超限」，又不必先 COUNT 一次
                .last("LIMIT " + (limit + 1)));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public UserStatusResultVO disableUser(Long id, UserDisableDTO dto) {
        SysUser user = requireUser(id);
        if (RoleConstants.ADMIN.equals(user.getRole())) {
            // 管理员互封是权限事故的常见起点：一旦有两个管理员互相封禁，
            // 系统就再也没有人能解封。真要停用管理员账号，应该直接改库并走线下审批
            throw new BusinessException(ResultCode.CANNOT_DISABLE_ADMIN);
        }
        if (AccountStatus.DISABLED.equals(user.getStatus())) {
            throw new BusinessException(ResultCode.USER_DISABLED);
        }

        String reason = dto.getReason().trim();
        sysUserMapper.update(null, Wrappers.<SysUser>lambdaUpdate()
                .eq(SysUser::getId, id)
                .eq(SysUser::getStatus, AccountStatus.NORMAL)
                .set(SysUser::getStatus, AccountStatus.DISABLED)
                .set(SysUser::getRemark, reason));

        // 双重失效：Redis 标记让 JwtAuthenticationFilter 就地返回 403（验收要求「立即」），
        // 密码版本 +1 让所有已签发令牌在过滤器第一关就失效（兜住标记被误删的情况）
        tokenStore.markBanned(id);
        tokenStore.bumpPasswordVersion(id);

        Map<String, Object> params = new HashMap<>();
        params.put("content", "您的账号已被封禁，原因：" + reason + "。如有疑问请联系平台管理员。");
        messageService.send(id, MessageType.SYSTEM_NOTICE, null, params);

        writeOperLog(OperType.DISABLE_USER, OperTargetType.USER, id, user.getUsername(),
                AccountStatus.NORMAL, AccountStatus.DISABLED, reason);

        log.info("用户已封禁 | userId={} | adminId={}", id, SecurityUtils.currentUserId());
        return UserStatusResultVO.of(id, AccountStatus.DISABLED);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public UserStatusResultVO enableUser(Long id, UserEnableDTO dto) {
        SysUser user = requireUser(id);
        if (!AccountStatus.DISABLED.equals(user.getStatus())) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "该用户当前未被封禁");
        }

        sysUserMapper.update(null, Wrappers.<SysUser>lambdaUpdate()
                .eq(SysUser::getId, id)
                .eq(SysUser::getStatus, AccountStatus.DISABLED)
                .set(SysUser::getStatus, AccountStatus.NORMAL)
                .set(SysUser::getRemark, trimmed(dto.getRemark())));

        tokenStore.unmarkBanned(id);
        // 密码版本再 +1：封禁期间旧令牌已在过滤器失效，但版本号不递增的话，
        // 「封禁时递增过的版本」仍然匹配，旧令牌会随标记擦除而复活
        tokenStore.bumpPasswordVersion(id);

        Map<String, Object> params = new HashMap<>();
        params.put("content", "您的账号已恢复正常，可以继续使用。");
        messageService.send(id, MessageType.SYSTEM_NOTICE, null, params);

        writeOperLog(OperType.ENABLE_USER, OperTargetType.USER, id, user.getUsername(),
                AccountStatus.DISABLED, AccountStatus.NORMAL, trimmed(dto.getRemark()));

        log.info("用户已解封 | userId={} | adminId={}", id, SecurityUtils.currentUserId());
        return UserStatusResultVO.of(id, AccountStatus.NORMAL);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResetPasswordResultVO resetPassword(Long id, ResetPasswordDTO dto) {
        SysUser user = requireUser(id);

        sysUserMapper.update(null, Wrappers.<SysUser>lambdaUpdate()
                .eq(SysUser::getId, id)
                .set(SysUser::getPassword, passwordEncoder.encode(DEFAULT_PASSWORD))
                .set(SysUser::getNeedChangePassword, 1));

        // 重置密码等于「凭据已换人掌握」，旧令牌必须立即失效，
        // 否则拿到旧令牌的人在新密码生效后仍能继续操作
        tokenStore.bumpPasswordVersion(id);

        Map<String, Object> params = new HashMap<>();
        params.put("content", "您的登录密码已被管理员重置，请使用默认密码登录并立即修改。");
        messageService.send(id, MessageType.SYSTEM_NOTICE, null, params);

        writeOperLog(OperType.RESET_PASSWORD, OperTargetType.USER, id, user.getUsername(),
                null, null, dto.getRemark().trim());

        log.info("用户密码已重置 | userId={} | adminId={}", id, SecurityUtils.currentUserId());
        return ResetPasswordResultVO.of(id, DEFAULT_PASSWORD);
    }

    /* ================================================================== */
    /* 8 / 9. 订单管理                                                     */
    /* ================================================================== */

    @Override
    public PageResult<AdminOrderVO> orderList(AdminOrderQuery query) {
        Page<CompanionOrder> page = orderMapper.selectPage(query.toMpPage(), orderWrapper(query));
        return PageResult.of(page, toAdminOrderVos(page.getRecords()));
    }

    @Override
    public List<CompanionOrder> queryOrdersForExport(AdminOrderQuery query, int limit) {
        return orderMapper.selectList(orderWrapper(query)
                .last("LIMIT " + (limit + 1)));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ArbitrateResultVO arbitrate(Long id, ArbitrateDTO dto) {
        OrderStatus target = OrderStatus.of(dto.getTargetStatus());
        if (target == null || !target.isAdminForceable()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "目标状态只能是 COMPLETED 或 CANCELLED");
        }

        // 先读一次拿 beforeStatus 用于日志；forceTerminal 内部还有一次读取与条件更新，
        // 真正的并发保护在 SQL 的 .eq(status, 读到的状态) 上，不依赖这一次读
        CompanionOrder before = orderMapper.selectById(id);
        if (before == null) {
            throw new BusinessException(ResultCode.ORDER_NOT_FOUND);
        }

        String result = dto.getResult().trim();
        StringBuilder remark = new StringBuilder("管理员纠纷处理：").append(result);
        if (Boolean.TRUE.equals(dto.getRefundToFamily())) {
            // 一期不做在线支付，这里只是一条记账线索：真要退款走线下，
            // 平台只把「该退」这件事留在状态日志与操作日志里
            remark.append("；标记退费给家属（线下结算）");
        }
        if (Boolean.TRUE.equals(dto.getPenaltyToCompanion())) {
            remark.append("；对陪诊员计违规");
        }

        CompanionOrder after = orderService.forceTerminal(id, target, remark.toString());
        LocalDateTime handleTime = LocalDateTime.now();

        // 双方各发一条：只通知家属会让陪诊员在事后才知道自己被判定有责任，
        // 而只通知陪诊员会让家属继续等一个不会到来的服务
        Map<String, Object> params = new HashMap<>();
        params.put("orderNo", before.getOrderNo());
        params.put("reason", result);
        MessageType messageType = target == OrderStatus.CANCELLED
                ? MessageType.ORDER_CANCELLED
                : MessageType.ORDER_COMPLETED;
        messageService.send(before.getFamilyId(), messageType, id, params);
        messageService.send(before.getCompanionId(), messageType, id, params);

        writeOperLog(OperType.ARBITRATE_ORDER, OperTargetType.ORDER, id, before.getOrderNo(),
                before.getStatus(), after.getStatus(), remark.toString());

        log.info("纠纷处理完成 | orderId={} | {} → {} | adminId={}",
                id, before.getStatus(), after.getStatus(), SecurityUtils.currentUserId());
        return ArbitrateResultVO.of(id, target, handleTime);
    }

    /* ================================================================== */
    /* 10 / 11. 投诉管理                                                   */
    /* ================================================================== */

    @Override
    public PageResult<ComplaintVO> complaintList(AdminComplaintQuery query) {
        LambdaQueryWrapper<Complaint> wrapper = Wrappers.lambdaQuery();
        if (StringUtils.hasText(query.getStatus())) {
            ComplaintStatus status = ComplaintStatus.of(query.getStatus());
            if (status == null) {
                throw new BusinessException(ResultCode.PARAM_ERROR, "投诉状态取值不合法：" + query.getStatus());
            }
            wrapper.eq(Complaint::getStatus, status.name());
        }
        if (StringUtils.hasText(query.getType())) {
            if (org.company.nianglin.constant.ComplaintType.of(query.getType()) == null) {
                throw new BusinessException(ResultCode.PARAM_ERROR, "投诉类型取值不合法：" + query.getType());
            }
            wrapper.eq(Complaint::getType, query.getType().trim().toUpperCase());
        }
        if (StringUtils.hasText(query.getKeyword())) {
            String keyword = query.getKeyword().trim();
            wrapper.and(w -> w.like(Complaint::getContent, keyword)
                    .or().like(Complaint::getOrderNo, keyword));
        }
        applyRange(wrapper, Complaint::getCreateTime, query.getStartDate(), query.getEndDate(), "提交时间");
        // 待处理的排在最前面：管理端列表的第一屏应该就是待办，而不是最新提交的
        wrapper.orderByAsc(Complaint::getStatus)
                .orderByDesc(Complaint::getCreateTime)
                .orderByDesc(Complaint::getId);

        Page<Complaint> page = complaintMapper.selectPage(query.toMpPage(), wrapper);
        List<Complaint> rows = page.getRecords();
        if (rows.isEmpty()) {
            return new PageResult<>(page.getTotal(), page.getCurrent(), page.getSize(), page.getPages(), List.of());
        }

        Set<Long> userIds = new LinkedHashSet<>();
        for (Complaint complaint : rows) {
            userIds.add(complaint.getComplainantId());
            userIds.add(complaint.getTargetUserId());
        }
        Map<Long, String> names = userNameResolver.resolveAll(userIds);

        List<ComplaintVO> records = new ArrayList<>(rows.size());
        for (Complaint complaint : rows) {
            records.add(ComplaintVO.of(complaint,
                    names.get(complaint.getComplainantId()),
                    names.get(complaint.getTargetUserId()),
                    parseEvidence(complaint.getEvidence())));
        }
        return PageResult.of(page, records);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ComplaintHandleResultVO handleComplaint(Long id, ComplaintHandleDTO dto) {
        Complaint complaint = complaintMapper.selectById(id);
        if (complaint == null) {
            throw new BusinessException(ResultCode.COMPLAINT_NOT_FOUND);
        }
        ComplaintStatus target = ComplaintStatus.of(dto.getStatus());
        if (target == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "投诉状态取值不合法：" + dto.getStatus());
        }
        ComplaintStatus current = ComplaintStatus.of(complaint.getStatus());
        if (current == null) {
            throw new BusinessException(ResultCode.CONFLICT, "投诉状态数据异常，请联系开发处理");
        }
        if (current == target) {
            throw new BusinessException(ResultCode.CONFLICT, "投诉已处于该状态");
        }
        if (!current.canTransitTo(target)) {
            // 状态机只可正向流转；回退（例如把已结案的投诉改回处理中）一律 409
            throw new BusinessException(ResultCode.CONFLICT,
                    "投诉状态不能从「" + current.getLabel() + "」变更为「" + target.getLabel() + "」");
        }

        LoginUser me = SecurityUtils.currentUser();
        LocalDateTime now = LocalDateTime.now();
        boolean terminal = target.isTerminal();

        complaintMapper.update(null, Wrappers.<Complaint>lambdaUpdate()
                .eq(Complaint::getId, id)
                .eq(Complaint::getStatus, current.name())
                .set(Complaint::getStatus, target.name())
                .set(Complaint::getHandleResult, dto.getHandleResult().trim())
                .set(Complaint::getPenaltyToTarget, Boolean.TRUE.equals(dto.getPenaltyToTarget()) ? 1 : 0)
                .set(Complaint::getHandleAdminId, me.userId())
                .set(Complaint::getHandleTime, now));

        if (terminal) {
            // 只在终态通知双方：中间态（处理中）也发的话，用户会收到一条
            // 「已处理完成」的消息，而实际处理还在进行
            Map<String, Object> params = new HashMap<>();
            params.put("orderNo", complaint.getOrderNo());
            params.put("handleResult", dto.getHandleResult().trim());
            messageService.send(complaint.getComplainantId(), MessageType.COMPLAINT_HANDLED, id, params);
            messageService.send(complaint.getTargetUserId(), MessageType.COMPLAINT_HANDLED, id, params);
        }

        writeOperLog(OperType.HANDLE_COMPLAINT, OperTargetType.COMPLAINT, id, complaint.getOrderNo(),
                current.name(), target.name(), dto.getHandleResult().trim());

        log.info("投诉已处理 | complaintId={} | {} → {} | adminId={}",
                id, current.name(), target.name(), me.userId());
        return ComplaintHandleResultVO.of(id, target, now);
    }

    /* ================================================================== */
    /* 12. 操作日志                                                        */
    /* ================================================================== */

    @Override
    public PageResult<OperLogVO> operLogList(OperLogQuery query) {
        LambdaQueryWrapper<AdminOperLog> wrapper = Wrappers.lambdaQuery();
        if (query.getOperatorId() != null) {
            wrapper.eq(AdminOperLog::getOperatorId, query.getOperatorId());
        }
        if (StringUtils.hasText(query.getOperType())) {
            OperType type = OperType.of(query.getOperType());
            if (type == null) {
                throw new BusinessException(ResultCode.PARAM_ERROR, "操作类型取值不合法：" + query.getOperType());
            }
            wrapper.eq(AdminOperLog::getOperType, type.name());
        }
        if (StringUtils.hasText(query.getTargetType())) {
            OperTargetType type = OperTargetType.of(query.getTargetType());
            if (type == null) {
                throw new BusinessException(ResultCode.PARAM_ERROR, "目标类型取值不合法：" + query.getTargetType());
            }
            wrapper.eq(AdminOperLog::getTargetType, type.name());
        }
        if (query.getTargetId() != null) {
            wrapper.eq(AdminOperLog::getTargetId, query.getTargetId());
        }
        // 时间区间用 ge / le 而不是 between：两个边界都要含端点，
        // 管理员输入 10:00:00–10:00:00 时应该能查到那一刻的操作
        if (query.getStartTime() != null) {
            wrapper.ge(AdminOperLog::getOperTime, query.getStartTime());
        }
        if (query.getEndTime() != null) {
            wrapper.le(AdminOperLog::getOperTime, query.getEndTime());
        }
        wrapper.orderByDesc(AdminOperLog::getOperTime).orderByDesc(AdminOperLog::getId);

        Page<AdminOperLog> page = operLogMapper.selectPage(query.toMpPage(), wrapper);
        return PageResult.of(page, page.getRecords().stream().map(OperLogVO::of).toList());
    }

    /* ================================================================== */
    /* 内部：装配                                                          */
    /* ================================================================== */

    /**
     * 批量装配管理端订单列表项。
     *
     * <p>三次批量查询（老人 / 陪诊员姓名 / 投诉映射）覆盖整页，
     * <b>不做逐条查询</b>：一页 20 条若逐条查，就是 60 次往返。</p>
     */
    private List<AdminOrderVO> toAdminOrderVos(List<CompanionOrder> orders) {
        if (orders.isEmpty()) {
            return List.of();
        }

        List<Long> elderIds = orders.stream()
                .map(CompanionOrder::getElderId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<Long, ElderProfile> elders = new HashMap<>();
        if (!elderIds.isEmpty()) {
            for (ElderProfile elder : orderReadMapper.selectEldersIgnoringLogicDelete(elderIds)) {
                elders.put(elder.getId(), elder);
            }
        }

        Map<Long, String> companionNames = userNameResolver.resolveAll(orders.stream()
                .map(CompanionOrder::getCompanionId)
                .filter(Objects::nonNull)
                .collect(LinkedHashSet::new, LinkedHashSet::add, LinkedHashSet::addAll));

        List<Long> orderIds = orders.stream().map(CompanionOrder::getId).toList();
        Map<Long, Long> complaintIds = new HashMap<>();
        for (Map<String, Object> row : adminReadMapper.selectLatestComplaintIds(orderIds)) {
            Object orderId = row.get("orderId");
            Object complaintId = row.get("complaintId");
            if (orderId != null && complaintId != null) {
                complaintIds.put(((Number) orderId).longValue(), ((Number) complaintId).longValue());
            }
        }

        List<AdminOrderVO> records = new ArrayList<>(orders.size());
        for (CompanionOrder order : orders) {
            ElderProfile elder = elders.get(order.getElderId());
            OrderVO base = OrderVO.ofList(order,
                    elder == null ? null : elder.getName(),
                    ageOf(elder),
                    companionNames.get(order.getCompanionId()));
            records.add(AdminOrderVO.of(base, complaintIds.get(order.getId())));
        }
        return records;
    }

    /** 关联订单数：一次查询覆盖整页用户 */
    private Map<Long, Integer> orderCountsOf(Collection<Long> userIds) {
        Map<Long, Integer> counts = new HashMap<>();
        if (userIds == null || userIds.isEmpty()) {
            return counts;
        }
        for (Map<String, Object> row : adminReadMapper.selectOrderCountsByUserIds(userIds)) {
            Object uid = row.get("uid");
            Object cnt = row.get("cnt");
            if (uid != null && cnt != null) {
                counts.put(((Number) uid).longValue(), ((Number) cnt).intValue());
            }
        }
        return counts;
    }

    /** 三种审核状态的数量；数量为 0 的状态也占位，前端角标才不会显示空白 */
    private Map<String, Long> auditStatusCounts() {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (AuditStatus status : AuditStatus.values()) {
            counts.put(status.name(), 0L);
        }
        for (Map<String, Object> row : adminReadMapper.selectAuditStatusCounts()) {
            Object status = row.get("status");
            Object cnt = row.get("cnt");
            if (status != null && cnt != null) {
                counts.put(String.valueOf(status), ((Number) cnt).longValue());
            }
        }
        return counts;
    }

    private Map<Long, SysUser> loadUsers(Collection<Long> userIds) {
        Map<Long, SysUser> users = new HashMap<>();
        Set<Long> distinct = new LinkedHashSet<>(userIds);
        distinct.remove(null);
        if (distinct.isEmpty()) {
            return users;
        }
        for (SysUser user : sysUserMapper.selectList(Wrappers.<SysUser>lambdaQuery()
                .in(SysUser::getId, distinct)
                .select(SysUser::getId, SysUser::getUsername, SysUser::getPhone))) {
            users.put(user.getId(), user);
        }
        return users;
    }

    /* ================================================================== */
    /* 内部：写日志（每个写操作的最后一件事）                               */
    /* ================================================================== */

    /**
     * 写入一条管理员操作日志。
     *
     * <p>在<b>事务内</b>写：操作与日志必须一起生效或一起回滚。
     * 若把日志放到事务提交后，一次因业务异常回滚的操作会留下日志 ——
     * 于是审计记录显示「已封禁」，而数据库里那个人其实还好好的，
     * 这种不一致比没有日志更危险（会让人对着日志做出错误判断）。</p>
     *
     * <p>操作人姓名取当前登录用户的姓名快照，IP 与请求路径从当前请求读。</p>
     */
    private void writeOperLog(OperType operType, OperTargetType targetType, Long targetId,
                              String targetDesc, String beforeStatus, String afterStatus, String remark) {
        LoginUser me = SecurityUtils.currentUser();
        AdminOperLog log = new AdminOperLog();
        log.setOperatorId(me.userId());
        log.setOperatorName(userNameResolver.resolve(me.userId()));
        log.setOperType(operType.name());
        log.setTargetType(targetType.name());
        log.setTargetId(targetId);
        // 只截断、不脱敏：订单号是审计时的检索键，把它遮掉就没法靠日志串起时间线；
        // 而「被操作的对象是谁」本来就是这条日志存在的理由
        log.setTargetDesc(truncate(targetDesc, MAX_TARGET_DESC_LENGTH));
        log.setBeforeStatus(beforeStatus);
        log.setAfterStatus(afterStatus);
        log.setRemark(remark);
        log.setRequestUrl(RequestInfoUtil.requestUrl());
        log.setRequestMethod(RequestInfoUtil.requestMethod());
        log.setIp(RequestInfoUtil.clientIp());
        log.setOperTime(LocalDateTime.now());
        operLogMapper.insert(log);
    }

    /* ================================================================== */
    /* 内部：查询条件                                                      */
    /* ================================================================== */

    /**
     * 管理端订单与用户列表的筛选条件（<b>导出复用同一份</b>）。
     *
     * <p>文档 §6 的验收项是「导出的数据必须与页面上当前筛选结果一致」。
     * 把筛选逻辑抽成方法、导出直接调用，是让这条验收项
     * <b>在结构上不可能被违反</b>的唯一办法 —— 两端各写一套时，
     * 差异只在某些条件组合下暴露，属于最难发现的一类 bug。</p>
     */
    private LambdaQueryWrapper<SysUser> userWrapper(AdminUserQuery query) {
        LambdaQueryWrapper<SysUser> wrapper = Wrappers.lambdaQuery();
        if (StringUtils.hasText(query.getRole())) {
            if (RoleConstants.Role.of(query.getRole()) == null) {
                throw new BusinessException(ResultCode.PARAM_ERROR, "角色取值不合法：" + query.getRole());
            }
            wrapper.eq(SysUser::getRole, query.getRole().trim().toUpperCase());
        }
        if (StringUtils.hasText(query.getStatus())) {
            wrapper.eq(SysUser::getStatus, query.getStatus().trim().toUpperCase());
        }
        if (StringUtils.hasText(query.getKeyword())) {
            String keyword = query.getKeyword().trim();
            wrapper.and(w -> w.like(SysUser::getUsername, keyword)
                    .or().like(SysUser::getNickname, keyword)
                    .or().like(SysUser::getRealName, keyword)
                    .or().like(SysUser::getPhone, keyword));
        }
        applyRange(wrapper, SysUser::getCreateTime, query.getStartDate(), query.getEndDate(), "注册时间");
        return wrapper.orderByDesc(SysUser::getCreateTime).orderByDesc(SysUser::getId);
    }

    /** 管理端订单列表的筛选条件（导出复用同一份） */
    private LambdaQueryWrapper<CompanionOrder> orderWrapper(AdminOrderQuery query) {
        LambdaQueryWrapper<CompanionOrder> wrapper = Wrappers.lambdaQuery();
        if (StringUtils.hasText(query.getStatus())) {
            List<String> statuses = new ArrayList<>();
            for (String raw : query.getStatus().split(",")) {
                String value = raw.trim();
                if (value.isEmpty()) {
                    continue;
                }
                OrderStatus status = OrderStatus.of(value);
                if (status == null) {
                    throw new BusinessException(ResultCode.PARAM_ERROR, "订单状态取值不合法：" + value);
                }
                statuses.add(status.name());
            }
            if (!statuses.isEmpty()) {
                wrapper.in(CompanionOrder::getStatus, statuses);
            }
        }
        if (StringUtils.hasText(query.getKeyword())) {
            String keyword = query.getKeyword().trim();
            wrapper.and(w -> w.like(CompanionOrder::getOrderNo, keyword)
                    .or().like(CompanionOrder::getHospital, keyword));
        }
        applyRange(wrapper, CompanionOrder::getCreateTime, query.getStartDate(), query.getEndDate(), "下单时间");
        if (Boolean.TRUE.equals(query.getHasComplaint())) {
            // 用子查询而不是「先查投诉再 IN 订单 ID」：投诉量可能上千，
            // 拼进 IN 列表既慢又可能在极端情况下超出 SQL 长度限制
            wrapper.inSql(CompanionOrder::getId,
                    "SELECT order_id FROM complaint WHERE deleted = 0");
        }
        return wrapper.orderByDesc(CompanionOrder::getCreateTime).orderByDesc(CompanionOrder::getId);
    }

    private void applyAuditStatus(LambdaQueryWrapper<CompanionAuditRecord> wrapper, String auditStatus) {
        if (!StringUtils.hasText(auditStatus)) {
            return;
        }
        AuditStatus status = AuditStatus.of(auditStatus);
        if (status == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "审核状态取值不合法：" + auditStatus);
        }
        wrapper.eq(CompanionAuditRecord::getAuditStatus, status.name());
    }

    /**
     * 资质申请的「姓名 / 手机号」模糊搜索。
     *
     * <p>手机号不在 {@code companion_audit_record} 上（那张表只存申请材料），
     * 所以必须先按手机号在 {@code sys_user} 里查出候选用户 ID，再回过来筛申请记录。
     * 候选人集合为空时<b>只 retain 姓名条件</b>，不能写成
     * {@code IN ()} —— 那是语法错误，会让整个接口 500。</p>
     */
    private void applyKywordOnApplicant(LambdaQueryWrapper<CompanionAuditRecord> wrapper, String keyword) {
        if (!StringUtils.hasText(keyword)) {
            return;
        }
        String kw = keyword.trim();
        List<Long> candidateIds = sysUserMapper.selectList(Wrappers.<SysUser>lambdaQuery()
                        .and(w -> w.like(SysUser::getPhone, kw).or().like(SysUser::getUsername, kw))
                        .select(SysUser::getId))
                .stream().map(SysUser::getId).toList();

        if (candidateIds.isEmpty()) {
            wrapper.like(CompanionAuditRecord::getRealName, kw);
            return;
        }
        wrapper.and(w -> w.like(CompanionAuditRecord::getRealName, kw)
                .or().in(CompanionAuditRecord::getApplicantUserId, candidateIds));
    }

    private void applySubmitTimeRange(LambdaQueryWrapper<CompanionAuditRecord> wrapper,
                                      String startDate, String endDate) {
        applyRange(wrapper, CompanionAuditRecord::getSubmitTime, startDate, endDate, "提交时间");
    }

    /**
     * 通用日期区间条件：{@code [startDate 00:00, endDate 23:59:59]}。
     *
     * <p>结束日用 {@code le 23:59:59.999999999} 而不是 {@code lt 次日 00:00}：
     * 两种写法等价，但前者与「含当天」这句业务描述在代码上长得更像，
     * 审阅时不必在脑子里做一次换日换算。</p>
     */
    private <T> void applyRange(LambdaQueryWrapper<T> wrapper,
                                com.baomidou.mybatisplus.core.toolkit.support.SFunction<T, ?> column,
                                String startDate, String endDate, String field) {
        LocalDate start = parseDate(startDate, field + "起");
        LocalDate end = parseDate(endDate, field + "止");
        if (start != null && end != null && start.isAfter(end)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, field + "区间的开始日期不能晚于结束日期");
        }
        if (start != null) {
            wrapper.ge(column, start.atStartOfDay());
        }
        if (end != null) {
            wrapper.le(column, end.atTime(23, 59, 59));
        }
    }

    /* ================================================================== */
    /* 内部：文本与 JSON                                                   */
    /* ================================================================== */

    private SysUser requireUser(Long id) {
        SysUser user = sysUserMapper.selectById(id);
        if (user == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "用户不存在");
        }
        return user;
    }

    /** 解密后立即脱敏；明文不离开本方法 */
    private String maskedIdCard(String cipherText) {
        if (!StringUtils.hasText(cipherText)) {
            return null;
        }
        return MaskUtil.idCard(AesUtil.decrypt(cipherText, securityProperties.idCardKey()));
    }

    /** 解析证件 JSON；内容损坏时按空处理，不让一条脏数据把列表打挂 */
    private List<AuditApplicationVO.Certificate> parseCertificates(String json) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            List<CertificateItem> parsed = objectMapper.readValue(json,
                    new TypeReference<List<CertificateItem>>() {
                    });
            if (parsed == null) {
                return List.of();
            }
            List<AuditApplicationVO.Certificate> result = new ArrayList<>(parsed.size());
            for (CertificateItem item : parsed) {
                if (item == null) {
                    continue;
                }
                result.add(new AuditApplicationVO.Certificate()
                        .setName(item.getName())
                        .setUrl(item.getUrl()));
            }
            return result;
        } catch (Exception e) {
            log.warn("证件材料 JSON 解析失败，已按空处理 | len={} | {}", json.length(), e.getMessage());
            return List.of();
        }
    }

    /** 解析投诉证据 JSON；内容损坏时按空处理 */
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

    private static String trimmed(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    /** 按列宽截断；{@code null} 原样返回 */
    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    /** 由出生日期算年龄；出生日期缺失返回 {@code null} */
    private static Integer ageOf(ElderProfile elder) {
        if (elder == null || elder.getBirthDate() == null) {
            return null;
        }
        return Period.between(elder.getBirthDate(), LocalDate.now()).getYears();
    }
}

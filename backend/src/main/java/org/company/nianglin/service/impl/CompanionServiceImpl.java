package org.company.nianglin.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.company.nianglin.common.ResultCode;
import org.company.nianglin.constant.AuditStatus;
import org.company.nianglin.constant.WorkStatus;
import org.company.nianglin.dto.CertificateItem;
import org.company.nianglin.dto.CompanionApplyDTO;
import org.company.nianglin.entity.CompanionAuditRecord;
import org.company.nianglin.entity.CompanionProfile;
import org.company.nianglin.exception.BusinessException;
import org.company.nianglin.mapper.CompanionAuditRecordMapper;
import org.company.nianglin.mapper.CompanionProfileMapper;
import org.company.nianglin.security.SecurityProperties;
import org.company.nianglin.security.SecurityUtils;
import org.company.nianglin.service.CompanionService;
import org.company.nianglin.util.AesUtil;
import org.company.nianglin.vo.CompanionApplicationVO;
import org.company.nianglin.vo.CompanionApplyResultVO;
import org.company.nianglin.vo.CompanionProfileVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 陪诊员资质服务实现。
 *
 * <p>一次申请会写<b>两张表</b>，这是刻意的：</p>
 *
 * <ul>
 *   <li>{@code companion_audit_record} —— <b>申请流水</b>，一次一条，永不覆盖。
 *       被驳回后重新提交会产生新记录，所以「这个人被驳回过几次、每次什么原因」是可追溯的；</li>
 *   <li>{@code companion_profile} —— <b>当前状态快照</b>，一个账号一条。
 *       订单模块、列表页要的是「他现在能不能接单」，让它们每次都去
 *       {@code MAX(submit_time)} 查流水，既慢又容易写错。</li>
 * </ul>
 *
 * <p>两张表在同一个事务里更新，不会出现「流水说待审核、快照说已通过」的裂缝。</p>
 *
 * @author 银龄伴诊团队
 * @since M3
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CompanionServiceImpl implements CompanionService {

    private final CompanionAuditRecordMapper auditRecordMapper;
    private final CompanionProfileMapper companionProfileMapper;
    private final ObjectMapper objectMapper;
    private final SecurityProperties securityProperties;

    /* ================================================================== */
    /* 提交申请                                                            */
    /* ================================================================== */

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CompanionApplyResultVO apply(CompanionApplyDTO dto) {
        Long userId = SecurityUtils.currentUserId();

        // 先对 companion_profile.user_id 加行锁/间隙锁：行存在时取记录锁，行不存在时
        // 借助唯一索引的间隙锁阻塞同一 user_id 的并发 INSERT，杜绝「双击提交或两标签页
        // 同时调用 apply 导致两条 PENDING 申请」的竞态
        companionProfileMapper.selectForUpdateByUserId(userId);

        Long pendingCount = auditRecordMapper.selectCount(Wrappers.<CompanionAuditRecord>lambdaQuery()
                .eq(CompanionAuditRecord::getApplicantUserId, userId)
                .eq(CompanionAuditRecord::getAuditStatus, AuditStatus.PENDING.name()));
        if (pendingCount != null && pendingCount > 0) {
            throw new BusinessException(ResultCode.PARAM_ERROR,
                    "您已有待审核的资质申请，请等待管理员处理");
        }

        CompanionProfile profile = findProfile(userId);
        if (profile != null && AuditStatus.APPROVED.name().equals(profile.getAuditStatus())) {
            throw new BusinessException(ResultCode.PARAM_ERROR,
                    "您的陪诊员资质已通过审核，无需重复提交");
        }

        CompanionAuditRecord record = new CompanionAuditRecord();
        record.setApplicantUserId(userId);
        record.setRealName(dto.getRealName().trim());
        record.setIdCard(encrypt(dto.getIdCard()));
        record.setServiceArea(dto.getServiceArea().trim());
        record.setAvailableTime(dto.getAvailableTime().trim());
        record.setCertificates(writeCertificates(dto.getCertificates()));
        record.setApplyRemark(clearable(dto.getRemark()));
        record.setAuditStatus(AuditStatus.PENDING.name());
        record.setSubmitTime(LocalDateTime.now());
        auditRecordMapper.insert(record);

        upsertProfile(userId, profile, dto);

        // 日志只打用户 ID 与申请 ID：姓名、身份证、证件清单一个都不能进日志
        log.info("陪诊员资质申请已提交 | userId={} | applicationId={} | 证件数={}",
                userId, record.getId(), dto.getCertificates().size());

        return CompanionApplyResultVO.of(record.getId());
    }

    /* ================================================================== */
    /* 查询自己的申请状态                                                   */
    /* ================================================================== */

    @Override
    public CompanionApplicationVO myApplication() {
        Long userId = SecurityUtils.currentUserId();
        List<CompanionAuditRecord> list = auditRecordMapper.selectList(
                Wrappers.<CompanionAuditRecord>lambdaQuery()
                        .eq(CompanionAuditRecord::getApplicantUserId, userId)
                        .orderByDesc(CompanionAuditRecord::getSubmitTime)
                        .orderByDesc(CompanionAuditRecord::getId)
                        .last("LIMIT 1"));
        return list.isEmpty() ? null : CompanionApplicationVO.of(list.get(0));
    }

    /* ================================================================== */
    /* 陪诊员公开资料                                                       */
    /* ================================================================== */

    @Override
    public CompanionProfileVO publicProfile(Long companionUserId) {
        CompanionProfile profile = findProfile(companionUserId);
        // 未通过审核的账号对外不算「陪诊员」：家属在下单页看到的名单里，
        // 不该混进还在审核中的人。申请人自己查进度走 /companion/application
        if (profile == null || !AuditStatus.APPROVED.name().equals(profile.getAuditStatus())) {
            throw new BusinessException(ResultCode.COMPANION_NOT_FOUND);
        }
        return CompanionProfileVO.ofPublic(profile);
    }

    /* ================================================================== */
    /* 内部工具                                                            */
    /* ================================================================== */

    private CompanionProfile findProfile(Long userId) {
        List<CompanionProfile> list = companionProfileMapper.selectList(
                Wrappers.<CompanionProfile>lambdaQuery()
                        .eq(CompanionProfile::getUserId, userId)
                        .orderByAsc(CompanionProfile::getId));
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * 写入或刷新「当前状态快照」。
     *
     * <p>被驳回后重新提交时，要把 {@code auditStatus} 拉回 {@code PENDING}
     * 并清掉上次的驳回原因 —— 否则前端会看到「待审核」的同时又看到一条驳回理由，
     * 用户会以为系统坏了。</p>
     *
     * <p>{@code score} / {@code orderCount} 等统计列<b>不动</b>：
     * 那些是历史业绩，重新申请资质不代表过去白干。</p>
     */
    private void upsertProfile(Long userId, CompanionProfile existing, CompanionApplyDTO dto) {
        String encryptedIdCard = encrypt(dto.getIdCard());

        if (existing == null) {
            CompanionProfile created = new CompanionProfile();
            created.setUserId(userId);
            created.setRealName(dto.getRealName().trim());
            created.setIdCard(encryptedIdCard);
            created.setServiceArea(dto.getServiceArea().trim());
            created.setAvailableTime(dto.getAvailableTime().trim());
            created.setAuditStatus(AuditStatus.PENDING.name());
            // 还没通过审核，语义上就是「不接单」，而不是库默认的 AVAILABLE
            created.setWorkStatus(WorkStatus.REST.name());
            created.setScore(BigDecimal.ZERO);
            created.setReviewCount(0);
            created.setOrderCount(0);
            created.setAcceptCount(0);
            companionProfileMapper.insert(created);
            return;
        }

        LambdaUpdateWrapper<CompanionProfile> update = Wrappers.<CompanionProfile>lambdaUpdate()
                .eq(CompanionProfile::getId, existing.getId())
                .set(CompanionProfile::getRealName, dto.getRealName().trim())
                .set(CompanionProfile::getIdCard, encryptedIdCard)
                .set(CompanionProfile::getServiceArea, dto.getServiceArea().trim())
                .set(CompanionProfile::getAvailableTime, dto.getAvailableTime().trim())
                .set(CompanionProfile::getAuditStatus, AuditStatus.PENDING.name())
                .set(CompanionProfile::getWorkStatus, WorkStatus.REST.name())
                .set(CompanionProfile::getRejectReason, null)
                .set(CompanionProfile::getAuditAdminId, null)
                .set(CompanionProfile::getAuditTime, null);
        companionProfileMapper.update(null, update);
    }

    /** 证件清单序列化成 JSON 字符串（列类型是 MySQL 的 json，由服务端保证一定合法） */
    private String writeCertificates(List<CertificateItem> certificates) {
        try {
            return objectMapper.writeValueAsString(certificates);
        } catch (JsonProcessingException e) {
            // 走到这里说明 CertificateItem 的字段没法被序列化，属于编码期错误，
            // 但绝不能把证件内容塞进异常消息 —— 那会顺着日志落盘
            log.error("证件清单序列化失败 | 证件数={}", certificates == null ? 0 : certificates.size(), e);
            throw new BusinessException(ResultCode.SYSTEM_ERROR, "证件材料处理失败，请重试");
        }
    }

    private String encrypt(String plainText) {
        if (!StringUtils.hasText(plainText)) {
            return null;
        }
        return AesUtil.encrypt(plainText.trim(), securityProperties.idCardKey());
    }

    private static String clearable(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}

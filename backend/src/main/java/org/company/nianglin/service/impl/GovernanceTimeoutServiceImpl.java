package org.company.nianglin.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.company.nianglin.constant.ComplaintStatus;
import org.company.nianglin.constant.MessageType;
import org.company.nianglin.constant.RoleConstants;
import org.company.nianglin.entity.Complaint;
import org.company.nianglin.entity.SysUser;
import org.company.nianglin.mapper.ComplaintMapper;
import org.company.nianglin.mapper.SysUserMapper;
import org.company.nianglin.service.GovernanceTimeoutService;
import org.company.nianglin.service.MessageService;
import org.company.nianglin.service.support.GovernanceTimeoutPolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 治理链超时扫描实现（closure-2026-11 · P1-6）。
 *
 * <p><b>只升级可见性，不改状态</b>：超时不是「自动驳回」的授权 ——
 * 投诉是否成立只有人能判。系统负责让管理员看见「这条卡住了」，处置仍然由人负责。</p>
 *
 * <p><b>不改 {@code update_time}</b>：动了它，这「未处理」就伪装成「刚处理过」，
 * 超时天数会被重置回 0，下一轮又变成「没超时」—— 提醒直接失效。</p>
 *
 * @author 银龄伴诊团队
 * @since closure-2026-11
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GovernanceTimeoutServiceImpl implements GovernanceTimeoutService {

    /** 提醒去重 key 前缀：{@code governance:timeout:complaint:{id}:{tier}} */
    private static final String REMIND_KEY_PREFIX = "governance:timeout:complaint:";

    private final ComplaintMapper complaintMapper;
    private final SysUserMapper sysUserMapper;
    private final MessageService messageService;
    private final StringRedisTemplate redisTemplate;

    /**
     * 投诉超时阈值（天）。
     *
     * <p>用 {@link Integer}：纯 Mockito 单测不起容器时为 {@code null}，
     * 按默认阈值 3 处理。</p>
     */
    @Value("${nianglin.complaint.processing-timeout-days:3}")
    private Integer complaintTimeoutDays;

    /** 单轮扫描上限，防止一批脏数据把一轮跑爆 */
    @Value("${nianglin.governance.timeout-scan-batch:200}")
    private Integer scanBatch;

    @Override
    public int scanOverdueComplaints() {
        int threshold = complaintTimeoutDays == null
                ? GovernanceTimeoutPolicy.DEFAULT_COMPLAINT_TIMEOUT_DAYS
                : complaintTimeoutDays;
        int batch = scanBatch == null || scanBatch <= 0 ? 200 : scanBatch;
        LocalDateTime now = LocalDateTime.now();

        List<Complaint> overdue = complaintMapper.selectList(Wrappers.<Complaint>lambdaQuery()
                .eq(Complaint::getStatus, ComplaintStatus.PROCESSING.name())
                // 从未推进过的投诉用 create_time 兜底 —— 否则 update_time 为空的记录永远查不出来
                .and(w -> w.lt(Complaint::getUpdateTime, now.minusDays(threshold))
                        .or(o -> o.isNull(Complaint::getUpdateTime)
                                .lt(Complaint::getCreateTime, now.minusDays(threshold))))
                .orderByAsc(Complaint::getUpdateTime)
                .last("LIMIT " + batch));
        if (overdue == null || overdue.isEmpty()) {
            return 0;
        }

        int notified = 0;
        for (Complaint complaint : overdue) {
            Integer days = GovernanceTimeoutPolicy.overdueDays(complaint.getStatus(),
                    complaint.getUpdateTime(), complaint.getCreateTime(), now, threshold);
            if (days == null) {
                continue;
            }
            int tier = GovernanceTimeoutPolicy.tierOf(days, threshold);
            if (!acquireReminder(complaint.getId(), tier, threshold)) {
                // 同一档位已经提醒过了 —— 不重复打扰管理员
                continue;
            }
            notifyAdmins(complaint, days);
            notified++;
        }
        log.info("治理链超时扫描完成 | 扫描={} 条 | 提醒={} 条", overdue.size(), notified);
        return notified;
    }

    /**
     * 同一档位只提醒一次。
     *
     * <p>Redis 不可用时<b>放行</b>：多提醒一次的后果远小于「一条卡了三个月的投诉没人看见」，
     * 与用药草稿的 {@code SET NX} 降级口径一致。</p>
     */
    private boolean acquireReminder(Long complaintId, int tier, int thresholdDays) {
        String key = REMIND_KEY_PREFIX + complaintId + ":" + tier;
        try {
            // TTL 覆盖一个档位周期：档位升上去之后 key 自然过期，可以再次提醒
            Boolean acquired = redisTemplate.opsForValue()
                    .setIfAbsent(key, "1", Duration.ofDays(Math.max(1, thresholdDays)));
            return Boolean.TRUE.equals(acquired);
        } catch (Exception e) {
            log.warn("提醒去重失败，本次按未提醒处理（Redis 不可用） | key={} | {}", key, e.getMessage());
            return false;
        }
    }

    /** 给全部管理员发一条超时提醒 */
    private void notifyAdmins(Complaint complaint, int overdueDays) {
        List<Long> adminIds = sysUserMapper.selectList(Wrappers.<SysUser>lambdaQuery()
                        .eq(SysUser::getRole, RoleConstants.ADMIN)
                        .select(SysUser::getId))
                .stream().map(SysUser::getId).toList();
        if (adminIds.isEmpty()) {
            return;
        }
        Map<String, Object> params = new HashMap<>(4);
        params.put("orderNo", complaint.getOrderNo());
        params.put("overdueDays", overdueDays);
        params.put("complaintId", complaint.getId());
        for (Long adminId : adminIds) {
            messageService.send(adminId, MessageType.COMPLAINT_TIMEOUT, complaint.getId(), params);
        }
    }
}

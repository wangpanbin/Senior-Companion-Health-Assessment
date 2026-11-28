package org.company.nianglin.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.company.nianglin.constant.ComplaintStatus;
import org.company.nianglin.constant.FeedbackStatus;
import org.company.nianglin.constant.MessageType;
import org.company.nianglin.constant.RoleConstants;
import org.company.nianglin.entity.Complaint;
import org.company.nianglin.entity.ProductFeedback;
import org.company.nianglin.entity.SysUser;
import org.company.nianglin.mapper.ComplaintMapper;
import org.company.nianglin.mapper.ProductFeedbackMapper;
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
 * <p><b>一个类扫两条链</b>：投诉（纠纷通道，默认 3 天）与产品反馈（建议通道，默认 7 天）
 * 共用同一份判定策略（{@link GovernanceTimeoutPolicy}）与幂等去重，只有阈值与消息类型不同。
 * 拆成两个类只会得到两份一模一样的「查询 → 判定 → 去重 → 提醒」骨架。</p>
 *
 * <p><b>只升级可见性，不改状态</b>：超时不是「自动驳回」的授权 ——
 * 投诉是否成立、反馈是否采纳只有人能判。系统负责让管理员看见「这条卡住了」，
 * 处置仍然由人负责。</p>
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

    /** 提醒去重 key 前缀：{@code governance:timeout:{type}:{id}:{tier}}（type = complaint / feedback） */
    private static final String REMIND_KEY_PREFIX = "governance:timeout:";

    private final ComplaintMapper complaintMapper;
    private final ProductFeedbackMapper feedbackMapper;
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

    /**
     * 产品反馈超时阈值（天）。
     *
     * <p>反馈是建议通道不是纠纷通道：没人催的反馈多放几天不影响任何人的钱与权益，
     * 阈值理应比投诉宽（7 天 vs 3 天）。</p>
     */
    @Value("${nianglin.feedback.processing-timeout-days:7}")
    private Integer feedbackTimeoutDays;

    /** 单轮扫描上限，防止一批脏数据把一轮跑爆 */
    @Value("${nianglin.governance.timeout-scan-batch:200}")
    private Integer scanBatch;

    @Override
    public int scanOverdueComplaints() {
        int threshold = thresholdDays(complaintTimeoutDays, GovernanceTimeoutPolicy.DEFAULT_COMPLAINT_TIMEOUT_DAYS);
        LocalDateTime now = LocalDateTime.now();

        List<Complaint> overdue = complaintMapper.selectList(Wrappers.<Complaint>lambdaQuery()
                .eq(Complaint::getStatus, ComplaintStatus.PROCESSING.name())
                // 从未推进过的投诉用 create_time 兜底 —— 否则 update_time 为空的记录永远查不出来
                .and(w -> w.lt(Complaint::getUpdateTime, now.minusDays(threshold))
                        .or(o -> o.isNull(Complaint::getUpdateTime)
                                .lt(Complaint::getCreateTime, now.minusDays(threshold))))
                .orderByAsc(Complaint::getUpdateTime)
                .last("LIMIT " + scanLimit()));
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
            if (!acquireReminder("complaint", complaint.getId(), tier, threshold)) {
                // 同一档位已经提醒过了 —— 不重复打扰管理员
                continue;
            }
            Map<String, Object> params = new HashMap<>(4);
            params.put("orderNo", complaint.getOrderNo());
            params.put("overdueDays", days);
            notifyAdmins(MessageType.COMPLAINT_TIMEOUT, complaint.getId(), params);
            notified++;
        }
        log.info("治理链超时扫描完成 | 扫描={} 条 | 提醒={} 条", overdue.size(), notified);
        return notified;
    }

    @Override
    public int scanOverdueFeedback() {
        int threshold = thresholdDays(feedbackTimeoutDays, GovernanceTimeoutPolicy.DEFAULT_FEEDBACK_TIMEOUT_DAYS);
        LocalDateTime now = LocalDateTime.now();

        List<ProductFeedback> overdue = feedbackMapper.selectList(Wrappers.<ProductFeedback>lambdaQuery()
                .eq(ProductFeedback::getStatus, FeedbackStatus.PROCESSING.name())
                // 与投诉链同款「create_time 兜底」：update_time 为空的记录也要能被扫到
                .and(w -> w.lt(ProductFeedback::getUpdateTime, now.minusDays(threshold))
                        .or(o -> o.isNull(ProductFeedback::getUpdateTime)
                                .lt(ProductFeedback::getCreateTime, now.minusDays(threshold))))
                .orderByAsc(ProductFeedback::getUpdateTime)
                .last("LIMIT " + scanLimit()));
        if (overdue == null || overdue.isEmpty()) {
            return 0;
        }

        int notified = 0;
        for (ProductFeedback feedback : overdue) {
            Integer days = GovernanceTimeoutPolicy.overdueDays(feedback.getStatus(),
                    feedback.getUpdateTime(), feedback.getCreateTime(), now, threshold);
            if (days == null) {
                continue;
            }
            int tier = GovernanceTimeoutPolicy.tierOf(days, threshold);
            if (!acquireReminder("feedback", feedback.getId(), tier, threshold)) {
                continue;
            }
            // 反馈没有订单号，用反馈 ID 定位（管理端列表按 ID 可查）
            Map<String, Object> params = Map.of(
                    "feedbackId", feedback.getId(),
                    "overdueDays", days);
            notifyAdmins(MessageType.FEEDBACK_TIMEOUT, feedback.getId(), params);
            notified++;
        }
        log.info("治理链超时扫描（反馈链）完成 | 扫描={} 条 | 提醒={} 条", overdue.size(), notified);
        return notified;
    }

    /** 阈值取值：单测不起容器时为 null，回落到策略里的默认值 */
    private static int thresholdDays(Integer configured, int defaultDays) {
        return configured == null ? defaultDays : configured;
    }

    /** 单轮扫描上限：配置缺失或非法时回落 200（与默认值一致） */
    private int scanLimit() {
        return scanBatch == null || scanBatch <= 0 ? 200 : scanBatch;
    }

    /**
     * 同一档位只提醒一次。
     *
     * <p>Redis 不可用时<b>放行</b>：多提醒一次的后果远小于「一条卡了三个月的投诉没人看见」，
     * 与用药草稿的 {@code SET NX} 降级口径一致。</p>
     */
    private boolean acquireReminder(String type, Long bizId, int tier, int thresholdDays) {
        String key = REMIND_KEY_PREFIX + type + ":" + bizId + ":" + tier;
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

    /** 给全部管理员发一条超时提醒（两条链共用：接收人与发送口径完全一致） */
    private void notifyAdmins(MessageType type, Long bizId, Map<String, Object> params) {
        List<Long> adminIds = sysUserMapper.selectList(Wrappers.<SysUser>lambdaQuery()
                        .eq(SysUser::getRole, RoleConstants.ADMIN)
                        .select(SysUser::getId))
                .stream().map(SysUser::getId).toList();
        if (adminIds.isEmpty()) {
            return;
        }
        for (Long adminId : adminIds) {
            messageService.send(adminId, type, bizId, params);
        }
    }
}

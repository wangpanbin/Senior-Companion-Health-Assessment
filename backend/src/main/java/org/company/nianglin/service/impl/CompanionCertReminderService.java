package org.company.nianglin.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.company.nianglin.constant.AuditStatus;
import org.company.nianglin.constant.MessageType;
import org.company.nianglin.constant.RedisKeyConstants;
import org.company.nianglin.constant.RoleConstants;
import org.company.nianglin.entity.CompanionProfile;
import org.company.nianglin.entity.SysUser;
import org.company.nianglin.mapper.CompanionProfileMapper;
import org.company.nianglin.mapper.SysUserMapper;
import org.company.nianglin.service.MessageService;
import org.company.nianglin.util.MaskUtil;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 健康证到期提醒（V5 · C1）。
 *
 * <h3>窗口与幂等</h3>
 *
 * <p>每天扫一次：已过审、已补录到期日、且到期日 ≤ 今天 + 7 天的档案
 * （<b>已过期的也在窗口内</b> —— 让管理员看到谁在持证过期上岗）。
 * 幂等靠 Redis {@code SET NX}：{@code cert:expire:remind:{profileId}:{expireDate}}，
 * 同一份到期日只提醒一次；换证后到期日变化 → 键变化 → 允许再提醒一轮。</p>
 *
 * <h3>收件人</h3>
 *
 * <p>本人 + 全部管理员（与投诉通知同法：管理员账号极少，群发成本可忽略）。
 * 同一 {@link MessageType} 靠 {@code audience} 占位符分流措辞，
 * 管理员侧姓名经 {@link MaskUtil#name} 脱敏。</p>
 *
 * <h3>失败隔离</h3>
 *
 * <p>逐条 try/catch：一个档案的发送失败不该让整批停下。
 * 失败时会把已抢到的幂等键<b>释放掉</b>并重新抛出 —— 这样「下一轮扫描会把漏掉的补上」
 * 才真正成立；否则键留在 Redis 里（TTL 30 天），这一份到期日就再也不会提醒了。</p>
 *
 * @author 银龄伴诊团队
 * @since V5
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CompanionCertReminderService {

    /** 同一份到期日提醒一次；TTL 30 天远大于提醒窗口，避免过早失效造成重复打扰 */
    private static final Duration REMIND_TTL = Duration.ofDays(30);

    private final CompanionProfileMapper companionProfileMapper;
    private final SysUserMapper sysUserMapper;
    private final MessageService messageService;
    private final StringRedisTemplate redisTemplate;

    /**
     * 扫描进入到期窗口的健康证并发送提醒。
     *
     * @return 本轮实际提醒的档案数（幂等键未抢到的不计）
     */
    public int scanExpiring() {
        LocalDate today = LocalDate.now();
        List<CompanionProfile> rows = companionProfileMapper.selectList(
                Wrappers.<CompanionProfile>lambdaQuery()
                        .eq(CompanionProfile::getAuditStatus, AuditStatus.APPROVED.name())
                        // 未补录的存量档案不进窗口：校验侧对空值放行，提醒侧同样不打扰
                        .isNotNull(CompanionProfile::getHealthCertExpire)
                        // ≤ 今天 + 7 天：既含「即将到期」也含「已过期」
                        .le(CompanionProfile::getHealthCertExpire, today.plusDays(7))
                        .select(CompanionProfile::getId, CompanionProfile::getUserId,
                                CompanionProfile::getRealName, CompanionProfile::getHealthCertExpire));
        if (rows.isEmpty()) {
            return 0;
        }

        // 管理员列表一轮只查一次：管理员账号极少，不该按档案数放大查询次数
        List<Long> adminIds = loadAdminIds();

        int sent = 0;
        for (CompanionProfile profile : rows) {
            try {
                if (remindOnce(profile, adminIds)) {
                    sent++;
                }
            } catch (Exception e) {
                // 单条失败不中断整批：remindOnce 已把幂等键释放掉，
                // 下一轮扫描能重新抢到并补上漏掉的这条
                log.warn("健康证到期提醒发送失败 | profileId={} | {}", profile.getId(), e.getMessage());
            }
        }
        return sent;
    }

    /**
     * 幂等键未抢到（今天已提醒过 / 其他实例先发了）时返回 false。
     *
     * <p>⚠️ 发送失败必须把幂等键还回去：键留着就等于「这一份到期日 30 天内不会再提醒」，
     * 类注释里承诺的「下一轮扫描会补上」就成了空话 —— 提醒漏发还是漏发，
     * 而且没有任何痕迹能说明它漏过。所以这里释放键后<b>重新抛出</b>，
     * 让 {@code scanExpiring} 既有的单条隔离逻辑继续生效（记 warn 并继续下一条）。</p>
     */
    private boolean remindOnce(CompanionProfile profile, List<Long> adminIds) {
        LocalDate expire = profile.getHealthCertExpire();
        if (expire == null || profile.getUserId() == null) {
            return false;
        }
        String key = RedisKeyConstants.CERT_EXPIRE_REMIND_PREFIX + profile.getId() + ":" + expire;
        Boolean first = redisTemplate.opsForValue().setIfAbsent(key, "1", REMIND_TTL);
        if (!Boolean.TRUE.equals(first)) {
            return false;
        }

        try {
            Map<String, Object> companionParams = new HashMap<>(4);
            companionParams.put("audience", "COMPANION");
            companionParams.put("expireDate", expire.toString());
            messageService.send(profile.getUserId(), MessageType.HEALTH_CERT_EXPIRING,
                    profile.getId(), companionParams);

            notifyAdmins(profile, expire, adminIds);
            log.info("健康证到期提醒已发送 | profileId={} | expire={}", profile.getId(), expire);
            return true;
        } catch (RuntimeException e) {
            releaseIdempotentKey(key, profile.getId());
            throw e;
        }
    }

    /**
     * 释放幂等键，让下一轮扫描能重试。
     *
     * <p>删除本身失败只记 warn：它不该盖掉真正的发送异常（那才是需要被看见的原因）。
     * 这种情况下这一份到期日会漏掉一次提醒，属于可接受的降级。</p>
     */
    private void releaseIdempotentKey(String key, Long profileId) {
        try {
            redisTemplate.delete(key);
        } catch (RuntimeException e) {
            log.warn("健康证提醒幂等键释放失败，本份到期日本轮不再重试 | profileId={} | {}",
                    profileId, e.getMessage());
        }
    }

    /** 管理员 ID 列表：管理员账号极少，一轮扫描查一次即可 */
    private List<Long> loadAdminIds() {
        return sysUserMapper.selectList(Wrappers.<SysUser>lambdaQuery()
                        .eq(SysUser::getRole, RoleConstants.ADMIN)
                        .select(SysUser::getId))
                .stream().map(SysUser::getId).toList();
    }

    private void notifyAdmins(CompanionProfile profile, LocalDate expire, List<Long> adminIds) {
        if (adminIds.isEmpty()) {
            log.warn("系统中没有管理员账号，健康证提醒未抄送管理员 | profileId={}", profile.getId());
            return;
        }
        Map<String, Object> adminParams = new HashMap<>(4);
        adminParams.put("audience", "ADMIN");
        // 管理员侧看到的姓名先脱敏：消息列表不是暴露完整姓名的场合（红线-2）
        adminParams.put("companionName", MaskUtil.name(profile.getRealName()));
        adminParams.put("expireDate", expire.toString());
        messageService.sendBatch(adminIds, MessageType.HEALTH_CERT_EXPIRING, profile.getId(), adminParams);
    }
}

package org.company.nianglin.service.support.medication;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.company.nianglin.constant.BindStatus;
import org.company.nianglin.constant.MessageType;
import org.company.nianglin.constant.OrderStatus;
import org.company.nianglin.constant.RoleConstants;
import org.company.nianglin.entity.CompanionOrder;
import org.company.nianglin.entity.ElderProfile;
import org.company.nianglin.entity.FamilyElderRelation;
import org.company.nianglin.entity.MedicationTask;
import org.company.nianglin.entity.SysUser;
import org.company.nianglin.mapper.CompanionOrderMapper;
import org.company.nianglin.mapper.ElderProfileMapper;
import org.company.nianglin.mapper.FamilyElderRelationMapper;
import org.company.nianglin.mapper.SysUserMapper;
import org.company.nianglin.service.MessageService;
import org.company.nianglin.service.sms.SmsReminderService;
import org.company.nianglin.util.ComplianceCheckUtil;
import org.company.nianglin.util.MaskUtil;
import org.company.nianglin.util.MessageTemplateUtil;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 漏服提醒的站内信实现（{@link ReminderDispatcher} 的默认渠道）。
 *
 * <h3>触达规则（docs/api/05-medication.md §2.1 · V5 多级升级）</h3>
 *
 * <ul>
 *   <li><b>L1 家属</b>：全部有效绑定家属推 {@code MEDICATION_REMIND} + 短信
 *       —— 逻辑与 M6 原 {@code notifyFamily} 一致，多推几条的代价远小于谁都没看到；</li>
 *   <li><b>L2 陪诊员</b>：老人<b>当天</b>（按 {@code visit_time} 落在当日区间判定）有
 *       {@code ACCEPTED} / {@code IN_SERVICE} 订单时，向该单接单陪诊员推
 *       {@code MEDICATION_MISSED} + 短信，{@code bizId=orderId}，点击跳陪诊员订单执行页。
 *       {@code PENDING} 订单不算 —— 还没接单，提醒一个「可能根本不会出现的人」没有意义；</li>
 *   <li><b>L3 管理员</b>：全部管理员推 {@code MEDICATION_ESCALATED}，<b>仅站内信</b>
 *       —— 管理员的入口是后台处置而不是短信，把升级当短信发只会变成噪音。</li>
 * </ul>
 *
 * <h3>文案合规闸</h3>
 *
 * <p>陪诊员侧文案在发送前渲染一次并过 {@link ComplianceCheckUtil} 词表，
 * 命中即拦截（宁可不发，也不让含医疗建议的句子出站）。
 * 家属侧模板是 M6 既定文案，参数同源（药名 + 脱敏姓名 + 时间），不含剂量。</p>
 *
 * <h3>失败隔离</h3>
 *
 * <p>两个渠道各自 try/catch：家属侧发不出去不该挡住陪诊员侧，
 * 任何一侧失败都不影响漏服判定本身（判定在调用方已完成）。</p>
 *
 * @author 银龄伴诊团队
 * @since V4
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InternalMessageReminderDispatcher implements ReminderDispatcher {

    private final MessageService messageService;
    private final ElderProfileMapper elderMapper;
    private final FamilyElderRelationMapper relationMapper;
    private final CompanionOrderMapper orderMapper;
    private final SmsReminderService smsReminderService;
    private final SysUserMapper sysUserMapper;

    @Override
    public void dispatchMissedReminder(MedicationTask task, int level) {
        ElderProfile elder = elderMapper.selectById(task.getElderId());

        Map<String, Object> params = new HashMap<>(4);
        // 姓名在进入站内信之前就脱敏：MessageService 不知道哪个字段是隐私，
        // 由调用方负责，这是 M8 定下的约定
        params.put("elderName", MaskUtil.name(elder == null ? null : elder.getName()));
        params.put("medicineName", task.getMedicineName());
        params.put("planTime", task.getPlanTime() == null ? null
                : task.getPlanTime().format(DateTimeFormatter.ofPattern("MM-dd HH:mm")));

        // 按级路由：每级只触达该级收件人，不重复抄送前级（V5 · §2.1）
        switch (level) {
            case ReminderLevelPolicy.LEVEL_FAMILY -> notifyFamily(task, params);
            case ReminderLevelPolicy.LEVEL_COMPANION ->
                    notifyOnDutyCompanion(task, params, queryFamilyIds(task));
            case ReminderLevelPolicy.LEVEL_ADMIN -> notifyAdmins(task, params);
            default -> log.warn("未知的漏服升级级别，跳过触达 | taskId={} | level={}", task.getId(), level);
        }
    }

    /**
     * 查询该老人全部有效绑定家属（L1 收件人与 L2 的排除名单同源）。
     *
     * <p>查询失败返回空列表：收件人拿不到时宁可不发，也不猜人。</p>
     */
    private List<Long> queryFamilyIds(MedicationTask task) {
        try {
            return relationMapper.selectList(Wrappers.<FamilyElderRelation>lambdaQuery()
                            .eq(FamilyElderRelation::getElderId, task.getElderId())
                            .eq(FamilyElderRelation::getStatus, BindStatus.BOUND.name())
                            // 只取需要推送的列，避免把整行（含关系备注等）读进内存
                            .select(FamilyElderRelation::getFamilyId))
                    .stream()
                    .map(FamilyElderRelation::getFamilyId)
                    .filter(Objects::nonNull)
                    .distinct()
                    .toList();
        } catch (Exception e) {
            log.warn("漏服提醒收件人查询失败（不影响漏服判定） | taskId={} | {}", task.getId(), e.getMessage());
            return List.of();
        }
    }

    /**
     * L1：给该老人所有有效绑定的家属推送漏服提醒（原 M6 逻辑迁入）。
     */
    private void notifyFamily(MedicationTask task, Map<String, Object> params) {
        List<Long> familyIds = queryFamilyIds(task);
        if (familyIds.isEmpty()) {
            log.warn("漏服提醒无家属接收人（该老人已无绑定家属） | taskId={} | elderId={}",
                    task.getId(), task.getElderId());
            return;
        }

        // 站内信与站外短信各自 try/catch：任一渠道失败都不该让另一渠道跟着不发
        try {
            // bizId 传 elderId：消息点击后跳「这位老人的用药页」，
            // 而不是跳到某一条任务 —— 家属要处理的是「今天还有哪些没吃」
            messageService.sendBatch(familyIds, MessageType.MEDICATION_REMIND, task.getElderId(), params);
        } catch (Exception e) {
            log.warn("漏服提醒（家属侧站内信）推送失败（不影响站外短信与漏服判定） | taskId={} | {}",
                    task.getId(), e.getMessage());
        }
        enqueueSmsQuietly(task, familyIds);
    }

    /**
     * 站外短信入队（V4 · P1-3）。
     *
     * <p>刻意放在站内信发送<b>之后</b>，并单独 try/catch：短信通道的配置错误、
     * 运营商超时都不应该让「站内信已经发出去了」这件事被回滚或误报为失败。</p>
     */
    private void enqueueSmsQuietly(MedicationTask task, List<Long> familyIds) {
        try {
            smsReminderService.enqueueMissedDose(task, familyIds);
        } catch (Exception e) {
            log.warn("漏服短信编排失败（不影响站内信与漏服判定） | taskId={} | {}",
                    task.getId(), e.getMessage());
        }
    }

    /** L2 陪诊员侧的短信入队（带升级级别，V5） */
    private void enqueueSmsQuietly(MedicationTask task, List<Long> recipients, int level) {
        try {
            smsReminderService.enqueueMissedDose(task, recipients, level);
        } catch (Exception e) {
            log.warn("漏服短信编排失败（不影响站内信与漏服判定） | taskId={} | {}",
                    task.getId(), e.getMessage());
        }
    }

    /**
     * 老人当天有 {@code ACCEPTED} / {@code IN_SERVICE} 订单时，向该单陪诊员同步提醒。
     *
     * <p>陪诊员此刻可能正陪在老人身边 —— 漏服提醒的时效价值在「当下能递一句话」，
     * 这是 E1a 把触达面扩到陪诊员的全部理由。</p>
     */
    private void notifyOnDutyCompanion(MedicationTask task, Map<String, Object> params, List<Long> familyIds) {
        try {
            LocalDate today = LocalDate.now();
            List<CompanionOrder> orders = orderMapper.selectList(Wrappers.<CompanionOrder>lambdaQuery()
                    .eq(CompanionOrder::getElderId, task.getElderId())
                    .in(CompanionOrder::getStatus,
                            OrderStatus.ACCEPTED.name(), OrderStatus.IN_SERVICE.name())
                    // 「当天」按就诊时间落在当日区间判定；用「< 次日 0 点」而不是
                    // 「<= 当日 23:59:59」，避免列精度升级后漏掉边界值
                    .ge(CompanionOrder::getVisitTime, today.atStartOfDay())
                    .lt(CompanionOrder::getVisitTime, today.plusDays(1).atStartOfDay())
                    .select(CompanionOrder::getId, CompanionOrder::getCompanionId));

            for (CompanionOrder order : orders) {
                Long companionId = order.getCompanionId();
                if (companionId == null) {
                    continue;
                }
                if (familyIds.contains(companionId)) {
                    // 同一人既是绑定家属又是接单陪诊员：已按家属身份收到，不重复轰炸
                    continue;
                }
                // 合规闸：渲染后的文案先过词表再出站（红线-1：不做诊断、不开药方，
                // 文案只含药名 + 时间 + 中性措辞，禁含剂量）
                String hit = ComplianceCheckUtil.firstHit(
                        MessageTemplateUtil.content(MessageType.MEDICATION_MISSED, params));
                if (hit != null) {
                    log.error("漏服提醒（陪诊员侧）文案命中合规词「{}」，已拦截 | taskId={} | orderId={}",
                            hit, task.getId(), order.getId());
                    continue;
                }
                messageService.send(companionId, MessageType.MEDICATION_MISSED, order.getId(), params);
                // V5 · L2：站外短信与站内信同批出站（级别进 dedup/日上限，V5 · A3 收敛）
                enqueueSmsQuietly(task, List.of(companionId), ReminderLevelPolicy.LEVEL_COMPANION);
                log.info("漏服提醒已同步陪诊员 | taskId={} | orderId={}", task.getId(), order.getId());
            }
        } catch (Exception e) {
            log.warn("漏服提醒（陪诊员侧）推送失败（不影响漏服判定） | taskId={} | {}", task.getId(), e.getMessage());
        }
    }

    /**
     * L3：把 24 小时未确认的漏服升级给全部管理员（仅站内信，V5）。
     *
     * <p>管理员账号在系统里数量极少（一期就几个），群发成本可忽略 ——
     * 与投诉通知同法。不入短信队列：管理员的处置入口是后台而不是短信。</p>
     */
    private void notifyAdmins(MedicationTask task, Map<String, Object> params) {
        try {
            List<Long> adminIds = sysUserMapper.selectList(Wrappers.<SysUser>lambdaQuery()
                            .eq(SysUser::getRole, RoleConstants.ADMIN)
                            .select(SysUser::getId))
                    .stream().map(SysUser::getId).toList();
            if (adminIds.isEmpty()) {
                log.warn("漏服升级提醒无管理员接收人 | taskId={}", task.getId());
                return;
            }
            // 合规闸：管理员侧文案同样是动态拼接（药名 + 时间），出站前过词表（红线-1）
            String hit = ComplianceCheckUtil.firstHit(
                    MessageTemplateUtil.content(MessageType.MEDICATION_ESCALATED, params));
            if (hit != null) {
                log.error("漏服升级提醒文案命中合规词「{}」，已拦截 | taskId={}", hit, task.getId());
                return;
            }
            // bizId 传 elderId：与家属侧同域（消息中心统一走详情页，不按 linkUrl 导航）
            messageService.sendBatch(adminIds, MessageType.MEDICATION_ESCALATED, task.getElderId(), params);
            log.info("漏服提醒已升级至管理员 | taskId={} | 管理员数={}", task.getId(), adminIds.size());
        } catch (Exception e) {
            log.warn("漏服升级提醒推送失败（不影响漏服判定） | taskId={} | {}", task.getId(), e.getMessage());
        }
    }
}

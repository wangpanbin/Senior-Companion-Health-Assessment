package org.company.nianglin.service.support.medication;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.company.nianglin.constant.BindStatus;
import org.company.nianglin.constant.MessageType;
import org.company.nianglin.constant.OrderStatus;
import org.company.nianglin.entity.CompanionOrder;
import org.company.nianglin.entity.ElderProfile;
import org.company.nianglin.entity.FamilyElderRelation;
import org.company.nianglin.entity.MedicationTask;
import org.company.nianglin.mapper.CompanionOrderMapper;
import org.company.nianglin.mapper.ElderProfileMapper;
import org.company.nianglin.mapper.FamilyElderRelationMapper;
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
 * <h3>触达规则（docs/api/05-medication.md §2.1）</h3>
 *
 * <ol>
 *   <li><b>家属</b>：全部有效绑定家属都推（{@code MEDICATION_REMIND}），
 *       逻辑与 M6 原 {@code notifyFamily} 一致 —— 多推几条的代价远小于谁都没看到。</li>
 *   <li><b>陪诊员</b>（V4 · E1a 新增）：老人<b>当天</b>（按 {@code visit_time}
 *       落在当日区间判定）有 {@code ACCEPTED} / {@code IN_SERVICE} 订单时，
 *       向该单接单陪诊员推 {@code MEDICATION_MISSED}，{@code bizId=orderId}，
 *       点击跳陪诊员订单执行页。{@code PENDING} 订单不算 —— 还没接单，
 *       提醒一个「可能根本不会出现的人」没有意义。</li>
 * </ol>
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

    @Override
    public void dispatchMissedReminder(MedicationTask task) {
        ElderProfile elder = elderMapper.selectById(task.getElderId());

        Map<String, Object> params = new HashMap<>(4);
        // 姓名在进入站内信之前就脱敏：MessageService 不知道哪个字段是隐私，
        // 由调用方负责，这是 M8 定下的约定
        params.put("elderName", MaskUtil.name(elder == null ? null : elder.getName()));
        params.put("medicineName", task.getMedicineName());
        params.put("planTime", task.getPlanTime() == null ? null
                : task.getPlanTime().format(DateTimeFormatter.ofPattern("MM-dd HH:mm")));

        List<Long> familyIds = notifyFamily(task, params);
        notifyOnDutyCompanion(task, params, familyIds);
    }

    /**
     * 给该老人所有有效绑定的家属推送漏服提醒（原 M6 逻辑迁入）。
     *
     * @return 实际触达（意图发送）的家属 ID 列表，供陪诊员侧排除重复接收人
     */
    private List<Long> notifyFamily(MedicationTask task, Map<String, Object> params) {
        try {
            List<Long> familyIds = relationMapper.selectList(Wrappers.<FamilyElderRelation>lambdaQuery()
                            .eq(FamilyElderRelation::getElderId, task.getElderId())
                            .eq(FamilyElderRelation::getStatus, BindStatus.BOUND.name())
                            // 只取需要推送的列，避免把整行（含关系备注等）读进内存
                            .select(FamilyElderRelation::getFamilyId))
                    .stream()
                    .map(FamilyElderRelation::getFamilyId)
                    .filter(Objects::nonNull)
                    .distinct()
                    .toList();

            if (familyIds.isEmpty()) {
                log.warn("漏服提醒无家属接收人（该老人已无绑定家属） | taskId={} | elderId={}",
                        task.getId(), task.getElderId());
                return List.of();
            }

            // bizId 传 elderId：消息点击后跳「这位老人的用药页」，
            // 而不是跳到某一条任务 —— 家属要处理的是「今天还有哪些没吃」
            messageService.sendBatch(familyIds, MessageType.MEDICATION_REMIND, task.getElderId(), params);
            enqueueSmsQuietly(task, familyIds);
            return familyIds;
        } catch (Exception e) {
            log.warn("漏服提醒（家属侧）推送失败（不影响漏服判定） | taskId={} | {}", task.getId(), e.getMessage());
            return List.of();
        }
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
                log.info("漏服提醒已同步陪诊员 | taskId={} | orderId={}", task.getId(), order.getId());
            }
        } catch (Exception e) {
            log.warn("漏服提醒（陪诊员侧）推送失败（不影响漏服判定） | taskId={} | {}", task.getId(), e.getMessage());
        }
    }
}

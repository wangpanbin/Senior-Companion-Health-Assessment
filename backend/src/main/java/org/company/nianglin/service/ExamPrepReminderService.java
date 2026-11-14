package org.company.nianglin.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.company.nianglin.constant.MessageType;
import org.company.nianglin.constant.OrderStatus;
import org.company.nianglin.constant.RedisKeyConstants;
import org.company.nianglin.entity.CompanionOrder;
import org.company.nianglin.entity.ElderProfile;
import org.company.nianglin.mapper.CompanionOrderMapper;
import org.company.nianglin.mapper.ElderProfileMapper;
import org.company.nianglin.util.ComplianceCheckUtil;
import org.company.nianglin.util.MessageTemplateUtil;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 就诊前一天检查准备提醒（V5 · B，docs/api/14-exam-prep.md §2）。
 *
 * <h3>窗口与收件人</h3>
 *
 * <p>每天扫一次：{@code visit_time} 落在<b>明天</b>、状态为
 * {@code PENDING / ACCEPTED / IN_SERVICE} 的订单（还没接单的单同样要提醒 ——
 * 准备责任在家属与老人，与谁接单无关）。收件人 = 下单家属 + 就诊老人本人
 * （ELDER 账号缺失时 {@code MessageService#send} 对 null 静默跳过）。</p>
 *
 * <h3>幂等与隔离</h3>
 *
 * <p>Redis {@code SET NX}：{@code exam-prep:remind:{orderId}:{visitDate}}，
 * TTL 48 小时 —— 同一订单同一就诊日只提醒一次，换就诊日（改期）键自然变化。
 * <b>键先占后发，发送失败立即释放</b>：否则一次 DB 抖动就让这一单在当天窗口内
 * 永久丢失（窗口只匹配「明天」一次）。配合<b>每小时</b>重扫，失败后下一轮即可补发 ——
 * at-least-once，重复风险由 NX 键 + 扫描分布式锁兜住。逐单 try/catch：一单失败不中断整批。</p>
 *
 * <h3>内容边界</h3>
 *
 * <p>消息只陈述「明天有就诊安排 + 医院 + 科室」这个事实，准备要求本身
 * 去查静态字典（{@code GET /api/exam-prep}）或助手 FAQ ——
 * 任何医疗准备内容都不由本服务生成（红线-1）。文案按 audience 分流：
 * 家属侧含「去智能助手查」指引（助手仅 FAMILY 可用），老人侧落到
 * 「以就诊医院要求为准」—— 不给老人一个进不去的入口。两侧文案各自过
 * {@link ComplianceCheckUtil} 词表，命中即拦截（宁可不发）。</p>
 *
 * @author 银龄伴诊团队
 * @since V5
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExamPrepReminderService {

    /** 单轮扫描上限：明天的订单是小集合，500 足够且不会拉爆内存 */
    private static final int SCAN_BATCH = 500;

    /** 幂等键 TTL 48 小时：覆盖「提前一天提醒」到「就诊当天结束」的完整窗口 */
    private static final Duration REMIND_TTL = Duration.ofHours(48);

    private static final DateTimeFormatter VISIT_DATE_FORMAT = DateTimeFormatter.ofPattern("MM月dd日");

    private final CompanionOrderMapper orderMapper;
    private final ElderProfileMapper elderMapper;
    private final MessageService messageService;
    private final StringRedisTemplate redisTemplate;

    /**
     * 扫描明天有就诊安排的在途订单并发送提醒。
     *
     * @return 本轮实际提醒的订单数（幂等键未抢到 / 被合规闸拦下的不计）
     */
    public int scanTomorrowOrders() {
        LocalDate tomorrow = LocalDate.now().plusDays(1);
        LocalDateTime windowStart = tomorrow.atStartOfDay();
        LocalDateTime windowEnd = windowStart.plusDays(1);

        List<CompanionOrder> orders = orderMapper.selectList(
                Wrappers.<CompanionOrder>lambdaQuery()
                        .in(CompanionOrder::getStatus,
                                OrderStatus.PENDING.name(),
                                OrderStatus.ACCEPTED.name(),
                                OrderStatus.IN_SERVICE.name())
                        .ge(CompanionOrder::getVisitTime, windowStart)
                        .lt(CompanionOrder::getVisitTime, windowEnd)
                        .orderByAsc(CompanionOrder::getVisitTime)
                        // 显式 LIMIT：分页插件 maxLimit=100 会把 selectPage 静默截断（MybatisPlusConfig），
                        // 而本任务每天（每小时）只扫一次，截断 = 超出部分静默丢失。参数是常量，无注入面
                        .last("LIMIT " + SCAN_BATCH));
        if (orders.isEmpty()) {
            return 0;
        }

        int sent = 0;
        for (CompanionOrder order : orders) {
            try {
                if (remindOnce(order, tomorrow)) {
                    sent++;
                }
            } catch (Exception e) {
                // 单订单失败不能影响整批：明天的提醒明天还有机会补（键没写成功就会重试）
                log.warn("检查准备提醒发送失败（已隔离） | orderId={} | {}", order.getId(), e.getMessage());
            }
        }
        return sent;
    }

    /** 幂等键未抢到（今天已提醒过 / 其他实例先发了）时返回 false */
    private boolean remindOnce(CompanionOrder order, LocalDate visitDate) {
        String key = RedisKeyConstants.EXAM_PREP_REMIND_PREFIX + order.getId() + ":" + visitDate;
        Boolean first = redisTemplate.opsForValue().setIfAbsent(key, "1", REMIND_TTL);
        if (!Boolean.TRUE.equals(first)) {
            return false;
        }

        Map<String, Object> base = new HashMap<>(4);
        base.put("visitDate", visitDate.format(VISIT_DATE_FORMAT));
        base.put("hospital", order.getHospital());
        base.put("department", order.getDepartment());

        // audience 分流：家属侧含助手指引（助手仅 FAMILY），老人侧落到医院须知
        Map<String, Object> familyParams = new HashMap<>(base);
        familyParams.put("audience", "FAMILY");
        Map<String, Object> elderParams = new HashMap<>(base);
        elderParams.put("audience", "ELDER");

        // 合规闸：两侧尾句不同，各自渲染后过词表（红线-1：不做诊断、不开药方）
        String familyHit = ComplianceCheckUtil.firstHit(
                MessageTemplateUtil.content(MessageType.ORDER_EXAM_PREP, familyParams));
        String elderHit = ComplianceCheckUtil.firstHit(
                MessageTemplateUtil.content(MessageType.ORDER_EXAM_PREP, elderParams));
        if (familyHit != null || elderHit != null) {
            log.error("检查准备提醒文案命中合规词「{}」，已拦截 | orderId={}",
                    familyHit != null ? familyHit : elderHit, order.getId());
            return false;
        }

        try {
            messageService.send(order.getFamilyId(), MessageType.ORDER_EXAM_PREP, order.getId(), familyParams);
            ElderProfile elder = elderMapper.selectById(order.getElderId());
            // 老人无账号（userId 为 null）时 send 静默跳过，家属侧照发
            messageService.send(elder == null ? null : elder.getUserId(),
                    MessageType.ORDER_EXAM_PREP, order.getId(), elderParams);
        } catch (Exception e) {
            // 键先占后发：失败必须释放，否则这一单在当天窗口内永久丢失
            // （窗口只匹配「明天」一次）。释放后，每小时重扫的下一轮可补发
            redisTemplate.delete(key);
            throw e;
        }
        log.info("检查准备提醒已发送 | orderId={} | visitDate={}", order.getId(), visitDate);
        return true;
    }
}

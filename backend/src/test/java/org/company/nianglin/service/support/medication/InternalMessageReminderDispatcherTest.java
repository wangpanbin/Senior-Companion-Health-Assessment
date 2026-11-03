package org.company.nianglin.service.support.medication;

import org.company.nianglin.constant.MessageType;
import org.company.nianglin.entity.ElderProfile;
import org.company.nianglin.entity.FamilyElderRelation;
import org.company.nianglin.entity.MedicationTask;
import org.company.nianglin.mapper.CompanionOrderMapper;
import org.company.nianglin.mapper.ElderProfileMapper;
import org.company.nianglin.mapper.FamilyElderRelationMapper;
import org.company.nianglin.service.MessageService;
import org.company.nianglin.service.sms.SmsReminderService;
import org.company.nianglin.support.MybatisLambdaCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 漏服提醒「站内信 ↔ 站外短信」失败隔离测试（V4 · P1-3）。
 *
 * <p>产品口径（docs/api/07-message.md §9）：两条渠道各自 try/catch，
 * 站内信失败不阻断短信，短信失败也不阻断站内信。这里只测编排层的调用关系，
 * 短信自身的偏好 / 退避 / 死信逻辑由 {@code SmsReminderServiceTest} 覆盖。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("漏服提醒：站内信与站外短信失败隔离")
class InternalMessageReminderDispatcherTest {

    private static final long ELDER_ID = 401L;
    private static final long TASK_ID = 20002L;
    private static final long FAMILY_ID = 101L;

    @Mock
    private MessageService messageService;
    @Mock
    private ElderProfileMapper elderMapper;
    @Mock
    private FamilyElderRelationMapper relationMapper;
    @Mock
    private CompanionOrderMapper orderMapper;
    @Mock
    private SmsReminderService smsReminderService;

    private InternalMessageReminderDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        // 纯 Mockito 单测不起 Spring 容器，lambda 列名缓存是空的
        MybatisLambdaCache.warmUp();
        dispatcher = new InternalMessageReminderDispatcher(
                messageService, elderMapper, relationMapper, orderMapper, smsReminderService);

        ElderProfile elder = new ElderProfile();
        elder.setId(ELDER_ID);
        elder.setName("张德海");
        when(elderMapper.selectById(ELDER_ID)).thenReturn(elder);

        FamilyElderRelation relation = new FamilyElderRelation();
        relation.setElderId(ELDER_ID);
        relation.setFamilyId(FAMILY_ID);
        when(relationMapper.selectList(any())).thenReturn(List.of(relation));

        // 当天无在途订单，陪诊员侧不参与本测试
        when(orderMapper.selectList(any())).thenReturn(List.of());
    }

    private MedicationTask task() {
        MedicationTask task = new MedicationTask();
        task.setId(TASK_ID);
        task.setElderId(ELDER_ID);
        task.setMedicineName("苯磺酸氨氯地平片");
        task.setPlanTime(LocalDateTime.of(2026, 11, 3, 8, 0));
        return task;
    }

    @Test
    @DisplayName("站内信发送失败时，站外短信仍然入队")
    void smsStillEnqueuedWhenInternalMessageFails() {
        MedicationTask task = task();
        doThrow(new RuntimeException("站内信写库失败"))
                .when(messageService).sendBatch(anyList(), eq(MessageType.MEDICATION_REMIND),
                        eq(ELDER_ID), any());

        dispatcher.dispatchMissedReminder(task);

        verify(smsReminderService).enqueueMissedDose(eq(task), eq(List.of(FAMILY_ID)));
    }

    @Test
    @DisplayName("站外短信入队失败时，站内信已发出且不漏服判定不受影响")
    void internalMessageKeptWhenSmsFails() {
        MedicationTask task = task();
        doThrow(new IllegalStateException("短信模板未配置"))
                .when(smsReminderService).enqueueMissedDose(any(), anyList());

        dispatcher.dispatchMissedReminder(task);

        verify(messageService).sendBatch(eq(List.of(FAMILY_ID)), eq(MessageType.MEDICATION_REMIND),
                eq(ELDER_ID), any());
    }

    @Test
    @DisplayName("家属关系查询失败时两条渠道都不发，且不抛异常")
    void noDispatchWhenRelationQueryFails() {
        MedicationTask task = task();
        when(relationMapper.selectList(any())).thenThrow(new RuntimeException("关系表查询失败"));

        dispatcher.dispatchMissedReminder(task);

        verify(messageService, never()).sendBatch(anyList(), any(), any(), any());
        verify(smsReminderService, never()).enqueueMissedDose(any(), anyList());
    }
}

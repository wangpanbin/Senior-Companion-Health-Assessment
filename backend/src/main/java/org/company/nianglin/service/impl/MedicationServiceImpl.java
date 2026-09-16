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
import org.company.nianglin.constant.BindStatus;
import org.company.nianglin.constant.DosageForm;
import org.company.nianglin.constant.MealRelation;
import org.company.nianglin.constant.MedicationPlanStatus;
import org.company.nianglin.constant.MedicationTaskStatus;
import org.company.nianglin.constant.MessageType;
import org.company.nianglin.constant.RoleConstants;
import org.company.nianglin.dto.MedicationCalendarQuery;
import org.company.nianglin.dto.MedicationPlanCreateDTO;
import org.company.nianglin.dto.MedicationPlanQuery;
import org.company.nianglin.dto.MedicationPlanUpdateDTO;
import org.company.nianglin.dto.MedicationTaskConfirmDTO;
import org.company.nianglin.dto.MedicineQuery;
import org.company.nianglin.entity.CompanionOrder;
import org.company.nianglin.entity.ElderProfile;
import org.company.nianglin.entity.FamilyElderRelation;
import org.company.nianglin.entity.MedicationPlan;
import org.company.nianglin.entity.MedicationTask;
import org.company.nianglin.entity.MedicineDict;
import org.company.nianglin.entity.SysUser;
import org.company.nianglin.exception.BusinessException;
import org.company.nianglin.mapper.CompanionOrderMapper;
import org.company.nianglin.mapper.ElderProfileMapper;
import org.company.nianglin.mapper.FamilyElderRelationMapper;
import org.company.nianglin.mapper.MedicationPlanMapper;
import org.company.nianglin.mapper.MedicationTaskMapper;
import org.company.nianglin.mapper.MedicineDictMapper;
import org.company.nianglin.mapper.SysUserMapper;
import org.company.nianglin.security.LoginUser;
import org.company.nianglin.security.SecurityUtils;
import org.company.nianglin.service.ElderService;
import org.company.nianglin.service.MedicationService;
import org.company.nianglin.service.MessageService;
import org.company.nianglin.util.MaskUtil;
import org.company.nianglin.util.MessageTemplateUtil;
import org.company.nianglin.vo.MedicationCalendarVO;
import org.company.nianglin.vo.MedicationConfirmVO;
import org.company.nianglin.vo.MedicationPlanVO;
import org.company.nianglin.vo.MedicationTaskVO;
import org.company.nianglin.vo.MedicineVO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * 用药管理服务实现。
 *
 * @author 银龄伴诊团队
 * @since M6
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MedicationServiceImpl implements MedicationService {

    /** 时间点入参格式：{@code HH:mm} */
    private static final DateTimeFormatter TIME_POINT_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    /** 判断「今天」时的时区口径与 {@code LocalDate.now()} 一致 */
    private static final TypeReference<List<String>> TIME_POINTS_TYPE = new TypeReference<>() {
    };

    private final MedicineDictMapper medicineMapper;
    private final MedicationPlanMapper planMapper;
    private final MedicationTaskMapper taskMapper;
    private final ElderProfileMapper elderMapper;
    private final FamilyElderRelationMapper relationMapper;
    private final CompanionOrderMapper orderMapper;
    private final SysUserMapper sysUserMapper;

    /** 归属校验复用 M3 的实现，绝不另写一套 */
    private final ElderService elderService;

    /** 漏服提醒复用 M8 的发送入口，模块内禁止自己 INSERT 站内信 */
    private final MessageService messageService;

    private final ObjectMapper objectMapper;

    /** 超过计划时间多少分钟仍未确认即判定漏服 */
    @Value("${nianglin.medication.missed-threshold-minutes:60}")
    private int missThresholdMinutes;

    /** 单次漏服扫描最多处理多少条，避免积压时一次拉爆内存 */
    @Value("${nianglin.medication.miss-scan-batch:200}")
    private int missScanBatch;

    /** 服药日历允许的最大区间天数 */
    @Value("${nianglin.medication.max-calendar-days:31}")
    private int maxCalendarDays;

    /* ================================================================== */
    /* 1. 药品字典                                                         */
    /* ================================================================== */

    @Override
    public PageResult<MedicineVO> dictPage(MedicineQuery query) {
        LambdaQueryWrapper<MedicineDict> wrapper = Wrappers.<MedicineDict>lambdaQuery();

        if (StringUtils.hasText(query.getKeyword())) {
            String keyword = query.getKeyword().trim();
            wrapper.and(w -> w.like(MedicineDict::getName, keyword)
                    .or().like(MedicineDict::getTradeName, keyword));
        }
        if (StringUtils.hasText(query.getDosageForm())) {
            // 取值非法直接抛错而不是返回空列表：返回空会让前端以为「这种剂型没有药」，
            // 而真实原因是参数拼错了
            DosageForm form = DosageForm.of(query.getDosageForm().trim());
            if (form == null) {
                throw new BusinessException(ResultCode.PARAM_ERROR, "剂型取值不合法");
            }
            wrapper.eq(MedicineDict::getDosageForm, form.name());
        }
        // 常用药置顶，其余按 ID 稳定排序 —— 不加第二排序键时同一批数据在分页间可能重排
        wrapper.orderByDesc(MedicineDict::getIsCommon).orderByAsc(MedicineDict::getId);

        Page<MedicineDict> page = medicineMapper.selectPage(query.toMpPage(), wrapper);
        return PageResult.of(page, MedicineVO::ofList);
    }

    @Override
    public MedicineVO dictDetail(Long medicineId) {
        MedicineDict medicine = medicineMapper.selectById(medicineId);
        if (medicine == null) {
            throw new BusinessException(ResultCode.MEDICINE_NOT_FOUND);
        }
        return MedicineVO.ofDetail(medicine);
    }

    /* ================================================================== */
    /* 2. 用药计划                                                         */
    /* ================================================================== */

    @Override
    public PageResult<MedicationPlanVO> planPage(MedicationPlanQuery query) {
        ElderProfile elder = requireElderAccess(query.getElderId());

        LambdaQueryWrapper<MedicationPlan> wrapper = Wrappers.<MedicationPlan>lambdaQuery()
                .eq(MedicationPlan::getElderId, query.getElderId());

        if (StringUtils.hasText(query.getStatus())) {
            MedicationPlanStatus status = MedicationPlanStatus.of(query.getStatus().trim());
            if (status == null) {
                throw new BusinessException(ResultCode.PARAM_ERROR, "计划状态取值不合法");
            }
            wrapper.eq(MedicationPlan::getStatus, status.name());
        }
        wrapper.orderByDesc(MedicationPlan::getCreateTime).orderByDesc(MedicationPlan::getId);

        Page<MedicationPlan> page = planMapper.selectPage(query.toMpPage(), wrapper);
        // 老人姓名在整页里是同一个值，只取一次，避免 N 次查库
        String elderName = elder.getName();
        List<MedicationPlanVO> records = page.getRecords().stream()
                .map(plan -> MedicationPlanVO.of(plan, elderName, parseTimePoints(plan.getTimePoints())))
                .toList();
        return PageResult.of(page, records);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createPlan(MedicationPlanCreateDTO dto) {
        LoginUser me = SecurityUtils.currentUser();

        // 写路径同样要过归属校验：控制器上的 hasRole('FAMILY') 只说明「你是家属」，
        // 说明不了「这个老人是你绑定的」
        ElderProfile elder = elderService.requireAccessible(dto.getElderId());

        MedicineDict medicine = medicineMapper.selectById(dto.getMedicineId());
        if (medicine == null) {
            throw new BusinessException(ResultCode.MEDICINE_NOT_FOUND);
        }

        List<String> timePoints = normalizeTimePoints(dto.getTimePoints(), dto.getFrequency());
        LocalDate startDate = parseDate(dto.getStartDate(), "startDate");
        LocalDate endDate = parseDate(dto.getEndDate(), "endDate");
        assertDateRange(startDate, endDate);
        String mealRelation = requireMealRelation(dto.getMealRelation());

        MedicationPlan plan = new MedicationPlan();
        plan.setElderId(dto.getElderId());
        plan.setMedicineId(medicine.getId());
        plan.setMedicineName(medicine.getName());
        plan.setDosage(dto.getDosage().trim());
        plan.setFrequency(dto.getFrequency());
        plan.setTimePoints(writeTimePoints(timePoints));
        plan.setStartDate(startDate);
        plan.setEndDate(endDate);
        plan.setMealRelation(mealRelation);
        plan.setStatus(MedicationPlanStatus.ACTIVE.name());
        plan.setRemark(trimToNull(dto.getRemark()));
        plan.setCreatedBy(me.userId());
        planMapper.insert(plan);

        // 文档要求「新增后不需要立即生成历史任务，只生成从今天起的任务」。
        // 这里把「今天」这一步即时做掉：否则家属刚建完计划，
        // 今日待服里空空如也，要等到明天 07:00 的定时任务看起来才生效，
        // 而实际上老人今天确实该吃药。
        LocalDate today = LocalDate.now();
        if (covers(plan, today)) {
            generateTasksForPlan(plan, today);
        }

        log.info("用药计划已创建 | planId={} | elderId={} | medicineId={} | operator={}",
                plan.getId(), dto.getElderId(), dto.getMedicineId(), me.userId());
        return plan.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updatePlan(Long planId, MedicationPlanUpdateDTO dto) {
        LoginUser me = SecurityUtils.currentUser();
        MedicationPlan plan = requirePlan(planId);
        elderService.requireAccessible(plan.getElderId());

        MedicationPlan patch = new MedicationPlan();

        if (dto.getMedicineId() != null) {
            MedicineDict medicine = medicineMapper.selectById(dto.getMedicineId());
            if (medicine == null) {
                throw new BusinessException(ResultCode.MEDICINE_NOT_FOUND);
            }
            patch.setMedicineId(medicine.getId());
            // 药品名是快照字段，换药必须同步换掉，否则列表上显示的药名与 medicineId 不符
            patch.setMedicineName(medicine.getName());
        }
        if (dto.getDosage() != null) {
            patch.setDosage(dto.getDosage().trim());
        }
        if (dto.getMealRelation() != null) {
            patch.setMealRelation(requireMealRelation(dto.getMealRelation()));
        }

        // frequency 与 timePoints 是一对约束，不能各改各的：
        // 只改次数会让「每日 2 次」下挂着 1 个时间点，日历上每天少一次服药记录
        Integer frequency = dto.getFrequency() != null ? dto.getFrequency() : plan.getFrequency();
        List<String> timePoints;
        if (dto.getTimePoints() != null) {
            timePoints = normalizeTimePoints(dto.getTimePoints(), frequency);
        } else {
            timePoints = parseTimePoints(plan.getTimePoints());
            if (frequency != null && timePoints.size() != frequency) {
                throw new BusinessException(ResultCode.PARAM_ERROR,
                        "修改每日次数时必须同时提供与之数量一致的时间点");
            }
        }
        patch.setFrequency(frequency);
        patch.setTimePoints(writeTimePoints(timePoints));

        LocalDate startDate = dto.getStartDate() == null
                ? plan.getStartDate()
                : parseDate(dto.getStartDate(), "startDate");
        // null = 不修改；空串 = 改为长期（清空结束日期）。两者必须区分开
        LocalDate endDate;
        if (dto.getEndDate() == null) {
            endDate = plan.getEndDate();
        } else if (!StringUtils.hasText(dto.getEndDate())) {
            endDate = null;
        } else {
            endDate = parseDate(dto.getEndDate(), "endDate");
        }
        assertDateRange(startDate, endDate);
        patch.setStartDate(startDate);
        patch.setEndDate(endDate);

        if (dto.getRemark() != null) {
            patch.setRemark(trimToNull(dto.getRemark()));
        }

        // 局部更新用 update(null, wrapper).set(...)：
        // updateById 会把实体里所有 null 字段一起写进去，
        // 于是「只想改备注」的请求会把剂量、时间点全清空
        applyPatch(planId, plan, patch);

        log.info("用药计划已修改 | planId={} | operator={}", planId, me.userId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void disablePlan(Long planId) {
        LoginUser me = SecurityUtils.currentUser();
        MedicationPlan plan = requirePlan(planId);
        elderService.requireAccessible(plan.getElderId());

        if (MedicationPlanStatus.DISABLED.name().equals(plan.getStatus())) {
            // 停用是幂等操作：重复点「停用」不该报错，前端可能因为网络重试
            log.info("用药计划已处于停用状态，忽略重复请求 | planId={}", planId);
            return;
        }

        planMapper.update(null, Wrappers.<MedicationPlan>lambdaUpdate()
                .eq(MedicationPlan::getId, planId)
                .eq(MedicationPlan::getStatus, MedicationPlanStatus.ACTIVE.name())
                .set(MedicationPlan::getStatus, MedicationPlanStatus.DISABLED.name()));

        // 「已生成的未确认任务标记为失效」：这里把「还没到点的」删掉，
        // 而不是把它们标成漏服。
        //
        // 因为漏服率是家属和答辩都会看的指标：计划停用之后本来就不该再吃药，
        // 把停用后的日子算成漏服，等于用系统的账错误地指责家属。
        // 至于已经过了时间点、确实没吃的那些任务，保持 PENDING 不动，
        // 让漏服扫描按统一规则把它们判为漏服 —— 那才是事实。
        int removed = taskMapper.delete(Wrappers.<MedicationTask>lambdaQuery()
                .eq(MedicationTask::getPlanId, planId)
                .eq(MedicationTask::getStatus, MedicationTaskStatus.PENDING.name())
                .gt(MedicationTask::getPlanTime, LocalDateTime.now()));

        log.info("用药计划已停用 | planId={} | 清理未到期任务={} 条 | operator={}", planId, removed, me.userId());
    }

    /* ================================================================== */
    /* 3. 服药任务                                                         */
    /* ================================================================== */

    @Override
    public MedicationCalendarVO calendar(MedicationCalendarQuery query) {
        ElderProfile elder = requireElderAccess(query.getElderId());

        LocalDate start = parseDate(query.getStartDate(), "startDate");
        LocalDate end = parseDate(query.getEndDate(), "endDate");
        assertDateRange(start, end);
        // 用 daysBetween 而不是 ChronoUnit：起点与终点是同一天时区间应为 1 天，
        // 用差值算会得到 0，导致「只查今天」被误判为非法区间
        long days = java.time.temporal.ChronoUnit.DAYS.between(start, end) + 1;
        if (days > maxCalendarDays) {
            throw new BusinessException(ResultCode.PARAM_ERROR,
                    "服药日历查询区间不能超过 " + maxCalendarDays + " 天");
        }

        List<MedicationTask> tasks = taskMapper.selectList(Wrappers.<MedicationTask>lambdaQuery()
                .eq(MedicationTask::getElderId, query.getElderId())
                .ge(MedicationTask::getPlanTime, start.atStartOfDay())
                // 结束日用「< 次日 0 点」而不是「<= 当日 23:59:59」，
                // 避免将来列精度变成 DATETIME(3) 后漏掉 23:59:59.500
                .lt(MedicationTask::getPlanTime, end.plusDays(1).atStartOfDay())
                .orderByAsc(MedicationTask::getPlanTime));

        return MedicationCalendarVO.of(query.getElderId(), elder.getName(), start, end,
                toTaskVOs(tasks));
    }

    @Override
    public List<MedicationTaskVO> todayTasks(Long elderId) {
        requireElderAccess(elderId);

        LocalDate today = LocalDate.now();
        List<MedicationTask> tasks = taskMapper.selectList(Wrappers.<MedicationTask>lambdaQuery()
                .eq(MedicationTask::getElderId, elderId)
                .ge(MedicationTask::getPlanTime, today.atStartOfDay())
                .lt(MedicationTask::getPlanTime, today.plusDays(1).atStartOfDay())
                .orderByAsc(MedicationTask::getPlanTime));
        return toTaskVOs(tasks);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MedicationConfirmVO confirm(Long taskId, MedicationTaskConfirmDTO dto) {
        LoginUser me = SecurityUtils.currentUser();

        MedicationTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "服药任务不存在");
        }
        requireElderAccess(task.getElderId());

        if (MedicationTaskStatus.TAKEN.name().equals(task.getStatus())) {
            throw new BusinessException(ResultCode.MEDICATION_TASK_CONFIRMED);
        }

        LocalDateTime confirmTime = StringUtils.hasText(dto.getConfirmTime())
                ? parseDateTime(dto.getConfirmTime())
                : LocalDateTime.now();
        if (confirmTime.isAfter(LocalDateTime.now().plusMinutes(1))) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "实际服药时间不能晚于当前时间");
        }

        // 补记：任务此前已被判为漏服时，最终状态仍是「已服」，
        // 但把 was_missed 置 1 —— 漏服率统计看的是这个标记，
        // 否则「漏了再补」会从漏服统计里凭空消失
        boolean wasMissed = MedicationTaskStatus.MISSED.name().equals(task.getStatus());

        var update = Wrappers.<MedicationTask>lambdaUpdate()
                .eq(MedicationTask::getId, taskId)
                // 条件更新把「当前状态」当作乐观锁：并发的两次确认只有一个能生效
                .eq(MedicationTask::getStatus, task.getStatus())
                .set(MedicationTask::getStatus, MedicationTaskStatus.TAKEN.name())
                .set(MedicationTask::getConfirmTime, confirmTime)
                .set(MedicationTask::getConfirmBy, me.userId());
        if (wasMissed) {
            update.set(MedicationTask::getWasMissed, 1);
        }
        if (dto.getRemark() != null) {
            update.set(MedicationTask::getConfirmRemark, trimToNull(dto.getRemark()));
        }
        int rows = taskMapper.update(null, update);
        if (rows == 0) {
            // 只有两种可能：别人已经确认了，或者别人已经把它判成漏服并推进了状态。
            // 重新读一次再决定报哪个错，比直接抛「已确认」更贴近事实
            MedicationTask latest = taskMapper.selectById(taskId);
            if (latest != null && MedicationTaskStatus.TAKEN.name().equals(latest.getStatus())) {
                throw new BusinessException(ResultCode.MEDICATION_TASK_CONFIRMED);
            }
            throw new BusinessException(ResultCode.CONFLICT, "该服药任务状态已变更，请刷新后重试");
        }

        MedicationTask updated = taskMapper.selectById(taskId);
        String operatorName = realNameOf(me.userId());
        log.info("服药任务已确认 | taskId={} | elderId={} | operator={} | 补记={}",
                taskId, task.getElderId(), me.userId(), wasMissed);
        return MedicationConfirmVO.of(updated, operatorName);
    }

    /* ================================================================== */
    /* 4. 定时任务入口（无登录上下文）                                      */
    /* ================================================================== */

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int generateDailyTasks(LocalDate date) {
        List<MedicationPlan> plans = planMapper.selectList(Wrappers.<MedicationPlan>lambdaQuery()
                .eq(MedicationPlan::getStatus, MedicationPlanStatus.ACTIVE.name())
                .le(MedicationPlan::getStartDate, date)
                // 长期用药的 end_date 为 NULL，必须显式放行，否则长期计划永远不生成任务
                .and(w -> w.isNull(MedicationPlan::getEndDate).or().ge(MedicationPlan::getEndDate, date)));

        if (plans.isEmpty()) {
            log.info("服药任务生成：当日无生效计划 | date={}", date);
            return 0;
        }

        int created = 0;
        for (MedicationPlan plan : plans) {
            created += generateTasksForPlan(plan, date);
        }
        log.info("服药任务生成完成 | date={} | 生效计划={} | 新增任务={}", date, plans.size(), created);
        return created;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int scanMissedTasks() {
        LocalDateTime deadline = LocalDateTime.now().minusMinutes(missThresholdMinutes);

        List<MedicationTask> overdue = taskMapper.selectPage(new Page<>(1, missScanBatch),
                Wrappers.<MedicationTask>lambdaQuery()
                        .eq(MedicationTask::getStatus, MedicationTaskStatus.PENDING.name())
                        .lt(MedicationTask::getPlanTime, deadline)
                        .orderByAsc(MedicationTask::getPlanTime)).getRecords();

        if (overdue.isEmpty()) {
            return 0;
        }

        int missed = 0;
        for (MedicationTask task : overdue) {
            // 条件更新即「认领」：置为 MISSED 且要求它此刻仍是 PENDING。
            // affected = 0 说明这一条刚被家属确认（或被另一个实例处理），直接跳过
            int rows = taskMapper.update(null, Wrappers.<MedicationTask>lambdaUpdate()
                    .eq(MedicationTask::getId, task.getId())
                    .eq(MedicationTask::getStatus, MedicationTaskStatus.PENDING.name())
                    .set(MedicationTask::getStatus, MedicationTaskStatus.MISSED.name()));
            if (rows == 0) {
                continue;
            }
            missed++;
            notifyFamily(task);
        }

        log.info("漏服扫描完成 | 阈值={} 分钟 | 判定漏服={} 条", missThresholdMinutes, missed);
        return missed;
    }

    /* ================================================================== */
    /* 内部：任务生成与漏服提醒                                             */
    /* ================================================================== */

    /**
     * 为一个计划生成某一天的全部服药任务。
     *
     * <p>幂等的三道防线，缺一不可：</p>
     * <ol>
     *   <li><b>先查后插</b>：先取出该日期已有的任务，跳过已存在的 (planId, planTime)。
     *       这挡住了「同一实例重复执行」。</li>
     *   <li><b>唯一索引</b> {@code uk_plan_date_time}：挡住并发实例之间的竞争。</li>
     *   <li><b>Redis 分布式锁</b>（在调用方 {@code MedicationScheduler}）：让正常情况下
     *       只有一个实例真的执行，前两道只是兜底。</li>
     * </ol>
     *
     * <p>为什么三层都要：只靠唯一索引的话，冲突会以异常形式抛在批量插入中间，
     * 一条已存在的记录就能让「补跑昨天」整批回滚；只靠先查后插又挡不住并发。</p>
     *
     * @return 实际新增条数
     */
    private int generateTasksForPlan(MedicationPlan plan, LocalDate date) {
        List<String> timePoints = parseTimePoints(plan.getTimePoints());
        if (timePoints.isEmpty()) {
            log.warn("用药计划缺少有效时间点，跳过生成 | planId={}", plan.getId());
            return 0;
        }

        Set<String> existing = new HashSet<>();
        for (MedicationTask task : taskMapper.selectList(Wrappers.<MedicationTask>lambdaQuery()
                .eq(MedicationTask::getPlanId, plan.getId())
                .eq(MedicationTask::getPlanDate, date)
                .select(MedicationTask::getId, MedicationTask::getPlanTime))) {
            existing.add(plan.getId() + "|" + task.getPlanTime());
        }

        int created = 0;
        for (String point : timePoints) {
            LocalDateTime planTime = date.atTime(LocalTime.parse(point, TIME_POINT_FORMAT));
            if (!existing.add(plan.getId() + "|" + planTime)) {
                continue;
            }

            MedicationTask task = new MedicationTask();
            task.setPlanId(plan.getId());
            task.setElderId(plan.getElderId());
            task.setMedicineId(plan.getMedicineId());
            // 三个快照字段：药品名、剂量、饭点关系。历史任务必须能独立解释自己，
            // 否则计划一改，过去的服药记录就跟着变了
            task.setMedicineName(plan.getMedicineName());
            task.setDosage(plan.getDosage());
            task.setMealRelation(plan.getMealRelation());
            task.setPlanDate(date);
            task.setPlanTime(planTime);
            task.setStatus(MedicationTaskStatus.PENDING.name());
            task.setWasMissed(0);
            task.setNotifySent(0);

            try {
                taskMapper.insert(task);
                created++;
            } catch (DuplicateKeyException e) {
                // 唯一索引兜住了并发写入。这属于「正常但需要留痕」的情况：
                // 如果频繁出现，说明分布式锁没生效，要去看 Redis 配置
                log.warn("服药任务已存在（唯一索引拦截） | planId={} | planTime={}", plan.getId(), planTime);
            }
        }
        return created;
    }

    /** 计划在指定日期是否生效（长期用药的 endDate 为 null 视为永远生效） */
    private static boolean covers(MedicationPlan plan, LocalDate date) {
        if (plan.getStartDate() != null && date.isBefore(plan.getStartDate())) {
            return false;
        }
        return plan.getEndDate() == null || !date.isAfter(plan.getEndDate());
    }

    /**
     * 给该老人所有有效绑定的家属推送漏服提醒。
     *
     * <p>推给<b>所有</b>绑定家属而不是只有「主要联系人」：主要联系人可能正在上班、
     * 手机静音，而漏服提醒的价值全在时效上。多推几条的代价远小于谁都没看到。</p>
     *
     * <p>推送失败不影响状态变更 —— 任务已经被判为漏服是事实，
     * 不能因为一条站内信发不出去就把这个判定回滚。</p>
     */
    private void notifyFamily(MedicationTask task) {
        try {
            ElderProfile elder = elderMapper.selectById(task.getElderId());
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
                log.warn("漏服提醒无接收人（该老人已无绑定家属） | taskId={} | elderId={}",
                        task.getId(), task.getElderId());
                return;
            }

            Map<String, Object> params = new HashMap<>(4);
            // 姓名在进入站内信之前就脱敏：MessageService 不知道哪个字段是隐私，
            // 由调用方负责，这是 M8 定下的约定
            params.put("elderName", MaskUtil.name(elder == null ? null : elder.getName()));
            params.put("medicineName", task.getMedicineName());
            params.put("planTime", task.getPlanTime() == null ? null
                    : task.getPlanTime().format(DateTimeFormatter.ofPattern("MM-dd HH:mm")));

            // bizId 传 elderId：消息点击后跳「这位老人的用药页」，
            // 而不是跳到某一条任务 —— 家属要处理的是「今天还有哪些没吃」
            messageService.sendBatch(familyIds, MessageType.MEDICATION_REMIND, task.getElderId(), params);

            taskMapper.update(null, Wrappers.<MedicationTask>lambdaUpdate()
                    .eq(MedicationTask::getId, task.getId())
                    .set(MedicationTask::getNotifySent, 1)
                    .set(MedicationTask::getNotifyTime, LocalDateTime.now()));
        } catch (Exception e) {
            log.warn("漏服提醒推送失败（不影响漏服判定） | taskId={} | {}", task.getId(), e.getMessage());
        }
    }

    /* ================================================================== */
    /* 内部：权限与查询                                                    */
    /* ================================================================== */

    /**
     * 老人档案的访问校验（读路径）。
     *
     * <p>比 {@code ElderService#requireAccessible} 多放行一类人：
     * <b>陪诊员 —— 前提是他在该老人身上有订单</b>。
     * 陪诊服务过程中需要看用药计划（比如老人问「这个药吃了没」），
     * 但陪诊员与老人之间没有绑定关系，只有订单关系，
     * 所以这里按订单反查，而不是给他开一个「所有老人都能看」的口子。</p>
     *
     * <p>没有订单的陪诊员依然拿到 {@code 2006}。</p>
     */
    private ElderProfile requireElderAccess(Long elderId) {
        if (elderId == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "老人档案 ID 不能为空");
        }
        LoginUser me = SecurityUtils.currentUser();
        if (RoleConstants.COMPANION.equals(me.role())) {
            // 只看「陪诊过程中」的单子：PENDING / ACCEPTED / IN_SERVICE。
            // 陪诊一旦 COMPLETED，账号与老人之间已经没有「陪诊关系」，
            // 不应该还能查老人用药计划 —— 否则 e2e 那种「6 个月前的单」仍然能让前任陪诊员读隐私。
            long related = orderMapper.selectCount(Wrappers.<CompanionOrder>lambdaQuery()
                    .eq(CompanionOrder::getElderId, elderId)
                    .eq(CompanionOrder::getCompanionId, me.userId())
                    .in(CompanionOrder::getStatus, List.of(
                            org.company.nianglin.constant.OrderStatus.PENDING.name(),
                            org.company.nianglin.constant.OrderStatus.ACCEPTED.name(),
                            org.company.nianglin.constant.OrderStatus.IN_SERVICE.name())));
            if (related <= 0) {
                throw new BusinessException(ResultCode.NO_PERMISSION_FOR_ELDER);
            }
            ElderProfile elder = elderMapper.selectById(elderId);
            if (elder == null) {
                throw new BusinessException(ResultCode.ELDER_NOT_FOUND);
            }
            return elder;
        }
        // FAMILY / ELDER / ADMIN 一律走 M3 那套已经测过的归属判断
        return elderService.requireAccessible(elderId);
    }

    private MedicationPlan requirePlan(Long planId) {
        MedicationPlan plan = planMapper.selectById(planId);
        if (plan == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "用药计划不存在");
        }
        return plan;
    }

    /**
     * 把 {@code patch} 里非 null 的字段写成一条局部更新 SQL。
     *
     * <p>不用 {@code updateById}：它会连同 null 字段一起写入，
     * 于是「只改备注」的请求会把没传的字段全部置空 —— 这在 M3 的档案局部更新上
     * 已经踩过一次，这里的语义必须保持一致。</p>
     *
     * <p>注意 {@code remark} / {@code endDate} 的「清空」意图：
     * 调用方把空串转成 {@code null} 写进 patch，此时这个字段需要
     * {@code set(col, null)} 而不是「跳过」。区分办法是比对原值 ——
     * 只有原值非空而 patch 为空时，才是真的清空。</p>
     */
    private void applyPatch(Long planId, MedicationPlan current, MedicationPlan patch) {
        var update = Wrappers.<MedicationPlan>lambdaUpdate().eq(MedicationPlan::getId, planId);

        setIfPresent(update, MedicationPlan::getMedicineId, patch.getMedicineId());
        setIfPresent(update, MedicationPlan::getMedicineName, patch.getMedicineName());
        setIfPresent(update, MedicationPlan::getDosage, patch.getDosage());
        setIfPresent(update, MedicationPlan::getFrequency, patch.getFrequency());
        setIfPresent(update, MedicationPlan::getTimePoints, patch.getTimePoints());
        setIfPresent(update, MedicationPlan::getMealRelation, patch.getMealRelation());
        setIfPresent(update, MedicationPlan::getStartDate, patch.getStartDate());
        clearable(update, MedicationPlan::getEndDate, current.getEndDate(), patch.getEndDate());
        clearable(update, MedicationPlan::getRemark, current.getRemark(), patch.getRemark());

        planMapper.update(null, update);
    }

    private <V> void setIfPresent(com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<MedicationPlan> update,
                                  com.baomidou.mybatisplus.core.toolkit.support.SFunction<MedicationPlan, V> column,
                                  V value) {
        if (value != null) {
            update.set(column, value);
        }
    }

    /** 可清空字段：patch 为 null 且原值非 null 时才写 NULL，否则只在有值时覆盖 */
    private <V> void clearable(com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<MedicationPlan> update,
                               com.baomidou.mybatisplus.core.toolkit.support.SFunction<MedicationPlan, V> column,
                               V currentValue, V newValue) {
        if (newValue != null) {
            update.set(column, newValue);
        } else if (currentValue != null) {
            update.set(column, null);
        }
    }

    /** 批量把任务实体转 VO，并一次性把确认人姓名查出来（避免逐条查库） */
    private List<MedicationTaskVO> toTaskVOs(List<MedicationTask> tasks) {
        if (tasks == null || tasks.isEmpty()) {
            return List.of();
        }
        Set<Long> userIds = new LinkedHashSet<>();
        for (MedicationTask task : tasks) {
            if (task.getConfirmBy() != null) {
                userIds.add(task.getConfirmBy());
            }
        }
        Map<Long, String> names = realNamesOf(userIds);

        List<MedicationTaskVO> result = new ArrayList<>(tasks.size());
        for (MedicationTask task : tasks) {
            result.add(MedicationTaskVO.of(task, names.get(task.getConfirmBy())));
        }
        return result;
    }

    private Map<Long, String> realNamesOf(Collection<Long> userIds) {
        Map<Long, String> names = new HashMap<>();
        if (userIds == null || userIds.isEmpty()) {
            return names;
        }
        for (SysUser user : sysUserMapper.selectList(Wrappers.<SysUser>lambdaQuery()
                .in(SysUser::getId, userIds)
                .select(SysUser::getId, SysUser::getRealName))) {
            names.put(user.getId(), user.getRealName());
        }
        return names;
    }

    private String realNameOf(Long userId) {
        if (userId == null) {
            return null;
        }
        SysUser user = sysUserMapper.selectById(userId);
        return user == null ? null : user.getRealName();
    }

    /* ================================================================== */
    /* 内部：校验与转换                                                    */
    /* ================================================================== */

    /**
     * 校验并规范化时间点。
     *
     * <p>去重 + 排序而不是原样存：{@code ["08:00","08:00"]} 会在同一天生成两条
     * 完全相同的任务，而唯一索引会让第二条静默失败 —— 用户看到「每日 2 次」
     * 却在日历上只有一条，很难解释。这里统一收敛掉。</p>
     */
    private List<String> normalizeTimePoints(List<String> raw, Integer frequency) {
        if (raw == null || raw.isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "服药时间点不能为空");
        }
        if (frequency == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "每日次数不能为空");
        }

        Set<String> distinct = new TreeSet<>();
        for (String point : raw) {
            if (!StringUtils.hasText(point)) {
                throw new BusinessException(ResultCode.PARAM_ERROR, "服药时间点不能为空");
            }
            String normalized = point.trim();
            try {
                distinct.add(LocalTime.parse(normalized, TIME_POINT_FORMAT).format(TIME_POINT_FORMAT));
            } catch (Exception e) {
                throw new BusinessException(ResultCode.PARAM_ERROR, "服药时间点格式应为 HH:mm：" + normalized);
            }
        }
        if (distinct.size() != frequency) {
            throw new BusinessException(ResultCode.PARAM_ERROR,
                    "服药时间点数量（" + distinct.size() + "）必须与每日次数（" + frequency + "）一致");
        }
        return new ArrayList<>(distinct);
    }

    private String requireMealRelation(String raw) {
        MealRelation relation = MealRelation.of(raw == null ? null : raw.trim());
        if (relation == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "与饭点关系取值不合法");
        }
        return relation.name();
    }

    private void assertDateRange(LocalDate start, LocalDate end) {
        if (start == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "开始日期不能为空");
        }
        if (end != null && end.isBefore(start)) {
            throw new BusinessException(ResultCode.MEDICATION_PLAN_INVALID);
        }
    }

    /** 解析时间点 JSON；内容损坏时返回空列表并告警，而不是让整个列表接口 500 */
    private List<String> parseTimePoints(String json) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            List<String> parsed = objectMapper.readValue(json, TIME_POINTS_TYPE);
            return parsed == null ? List.of() : parsed.stream().filter(StringUtils::hasText).toList();
        } catch (Exception e) {
            log.warn("服药时间点 JSON 解析失败，已按空处理 | len={} | {}", json.length(), e.getMessage());
            return List.of();
        }
    }

    private String writeTimePoints(List<String> timePoints) {
        try {
            return objectMapper.writeValueAsString(timePoints);
        } catch (Exception e) {
            // 序列化一个字符串列表理论上不会失败，真失败说明 ObjectMapper 配置被改了，
            // 属于系统级故障，不能静默写入一个坏值
            log.error("服药时间点序列化失败 | {}", e.getMessage());
            throw new BusinessException(ResultCode.SYSTEM_ERROR, "保存服药时间点失败，请稍后重试");
        }
    }

    private LocalDate parseDate(String value, String field) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (Exception e) {
            throw new BusinessException(ResultCode.PARAM_ERROR, field + " 日期格式应为 yyyy-MM-dd");
        }
    }

    private LocalDateTime parseDateTime(String value) {
        try {
            return LocalDateTime.parse(value.trim(),
                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        } catch (Exception e) {
            throw new BusinessException(ResultCode.PARAM_ERROR,
                    "实际服药时间格式应为 yyyy-MM-dd HH:mm:ss");
        }
    }

    /** 空白串归一成 {@code null}：避免库里出现「看起来有值、实际是空格」的备注 */
    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** 保留给未来的排序需求；当前列表统一按时间升序，不需要客户端指定 */
    @SuppressWarnings("unused")
    private static final Comparator<String> TIME_POINT_ORDER = Comparator.naturalOrder();

    /** 引用一次，确保 {@code MessageTemplateUtil} 的列宽约定与本模块截断口径一致 */
    @SuppressWarnings("unused")
    private static int contentMaxLength() {
        return MessageTemplateUtil.MAX_CONTENT_LENGTH;
    }
}

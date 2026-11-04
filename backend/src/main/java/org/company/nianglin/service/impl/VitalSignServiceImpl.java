package org.company.nianglin.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.company.nianglin.common.DateRange;
import org.company.nianglin.common.PageResult;
import org.company.nianglin.common.ResultCode;
import org.company.nianglin.constant.GlucoseContext;
import org.company.nianglin.constant.OrderStatus;
import org.company.nianglin.constant.VitalSource;
import org.company.nianglin.constant.VitalType;
import org.company.nianglin.dto.DateRangeQuery;
import org.company.nianglin.dto.VitalSignCreateDTO;
import org.company.nianglin.dto.VitalSignQuery;
import org.company.nianglin.dto.VitalTrendQuery;
import org.company.nianglin.entity.CompanionOrder;
import org.company.nianglin.entity.SysUser;
import org.company.nianglin.entity.VitalSignRecord;
import org.company.nianglin.exception.BusinessException;
import org.company.nianglin.mapper.CompanionOrderMapper;
import org.company.nianglin.mapper.SysUserMapper;
import org.company.nianglin.mapper.VitalSignRecordMapper;
import org.company.nianglin.security.SecurityUtils;
import org.company.nianglin.service.ElderService;
import org.company.nianglin.service.VitalSignService;
import org.company.nianglin.vo.VitalSignVO;
import org.company.nianglin.vo.VitalTrendPointVO;
import org.company.nianglin.vo.VitalTrendVO;
import org.company.nianglin.vo.VitalWithdrawVO;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 体征记录服务的实现（V4 · P2-2）。
 *
 * <h3>四条实现约定</h3>
 *
 * <ol>
 *   <li><b>数值上下限只防误录</b>：{@code 50–300 / 30–200 / 1.0–33.3 / 10.0–300.0}
 *       是最外层常量，<b>不对接口返回、不在前端展示、不命名为任何区间</b>；</li>
 *   <li><b>去重靠数据库唯一键</b>：业务层不做「先查后插」，
 *       直接捕获 {@link DuplicateKeyException} 转 {@code 2008} —— 并发下先查后插必漏；</li>
 *   <li><b>时间截断到分钟</b>：去重键是「老人 + 类型 + 分钟」，秒不截断会让
 *       同一分钟的两次录入都成功，唯一键形同虚设；</li>
 *   <li><b>撤回是逻辑删除</b>：行保留、{@code deleted=1}，
 *       生成列 {@code dedup_key} 自动置 {@code NULL} 释放唯一键槽位。</li>
 * </ol>
 *
 * @author 银龄伴诊团队
 * @since V4 · P2-2
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VitalSignServiceImpl implements VitalSignService {

    /** 趋势最多返回的点数；超出的部分取最近 N 条并如实标记 {@code truncated} */
    private static final int MAX_TREND_POINTS = 500;

    /* ---------------------------------------------------------------- */
    /* 输入合法性上下限（只防误录，不是医学口径）                          */
    /* ---------------------------------------------------------------- */

    private static final int MIN_SYSTOLIC = 50;
    private static final int MAX_SYSTOLIC = 300;
    private static final int MIN_DIASTOLIC = 30;
    private static final int MAX_DIASTOLIC = 200;
    private static final BigDecimal MIN_GLUCOSE = new BigDecimal("1.0");
    private static final BigDecimal MAX_GLUCOSE = new BigDecimal("33.3");
    private static final BigDecimal MIN_WEIGHT = new BigDecimal("10.0");
    private static final BigDecimal MAX_WEIGHT = new BigDecimal("300.0");

    /** 允许的「未来时间」容差：超过即视为填错 */
    private static final int FUTURE_TOLERANCE_MINUTES = 5;

    private final VitalSignRecordMapper vitalSignRecordMapper;
    private final CompanionOrderMapper companionOrderMapper;
    private final ElderService elderService;
    private final SysUserMapper sysUserMapper;

    /* ================================================================== */
    /* 家属端                                                              */
    /* ================================================================== */

    @Override
    public PageResult<VitalSignVO> listForFamily(Long elderId, VitalSignQuery query) {
        elderService.requireAccessible(elderId);
        return pageOf(elderId, null, query);
    }

    @Override
    public VitalTrendVO trendForFamily(Long elderId, VitalTrendQuery query) {
        elderService.requireAccessible(elderId);
        return trendOf(elderId, query);
    }

    @Override
    public VitalSignVO createForFamily(Long elderId, VitalSignCreateDTO dto) {
        elderService.requireAccessible(elderId);
        VitalSignRecord record = validatedRecord(dto, elderId);
        record.setSourceType(VitalSource.FAMILY_MANUAL.name());
        record.setOperatorUserId(SecurityUtils.currentUserId());
        record.setOrderId(null);
        return insert(record);
    }

    @Override
    public VitalWithdrawVO withdrawByFamily(Long recordId) {
        VitalSignRecord record = requireRecord(recordId);
        // 先做归属校验：不属于当前家属的老人一律 2006，不泄漏记录是否存在
        elderService.requireAccessible(record.getElderId());
        if (VitalSource.of(record.getSourceType()) != VitalSource.FAMILY_MANUAL
                || !SecurityUtils.currentUserId().equals(record.getOperatorUserId())) {
            throw new BusinessException(ResultCode.VITAL_SIGN_NOT_WITHDRAWABLE);
        }
        vitalSignRecordMapper.deleteById(recordId);
        log.info("家属已撤回体征记录 | recordId={} | elderId={} | operator={}",
                recordId, record.getElderId(), SecurityUtils.currentUserId());
        return VitalWithdrawVO.withdrawn(recordId);
    }

    /* ================================================================== */
    /* 老人端                                                              */
    /* ================================================================== */

    @Override
    public PageResult<VitalSignVO> listForElder(VitalSignQuery query) {
        return pageOf(requireSelfElderId(), null, query);
    }

    @Override
    public VitalTrendVO trendForElder(VitalTrendQuery query) {
        return trendOf(requireSelfElderId(), query);
    }

    /* ================================================================== */
    /* 陪诊员端                                                            */
    /* ================================================================== */

    @Override
    public VitalSignVO createForCompanion(Long orderId, VitalSignCreateDTO dto) {
        CompanionOrder order = requireOrderInService(orderId);
        VitalSignRecord record = validatedRecord(dto, order.getElderId());
        record.setSourceType(VitalSource.COMPANION_SERVICE.name());
        record.setOperatorUserId(SecurityUtils.currentUserId());
        record.setOrderId(orderId);
        return insert(record);
    }

    @Override
    public VitalWithdrawVO withdrawByCompanion(Long orderId, Long recordId) {
        requireOrderInService(orderId);
        VitalSignRecord record = requireRecord(recordId);
        if (!orderId.equals(record.getOrderId())) {
            // 记录不属于本单：按「无权操作该订单」处理，不暴露记录归属细节
            throw new BusinessException(ResultCode.ORDER_NO_PERMISSION);
        }
        if (VitalSource.of(record.getSourceType()) != VitalSource.COMPANION_SERVICE
                || !SecurityUtils.currentUserId().equals(record.getOperatorUserId())) {
            throw new BusinessException(ResultCode.VITAL_SIGN_NOT_WITHDRAWABLE);
        }
        vitalSignRecordMapper.deleteById(recordId);
        log.info("陪诊员已撤回体征记录 | recordId={} | orderId={} | operator={}",
                recordId, orderId, SecurityUtils.currentUserId());
        return VitalWithdrawVO.withdrawn(recordId);
    }

    @Override
    public PageResult<VitalSignVO> listForCompanion(Long orderId, VitalSignQuery query) {
        requireOrderReadable(orderId);
        return pageOf(null, orderId, query);
    }

    /**
     * 陪诊员链路唯一的准入判断：订单存在 → 本单陪诊员 → 服务中。
     *
     * <p>刻意<b>不调用</b> {@code ElderService#requireAccessible}：该方法按既定规则
     * 对 {@code COMPANION} 抛 {@code 2006}，改动它会放宽陪诊员查看老人档案的权限。
     * 这里改用订单自带的 {@code elderId} 定位老人，权限收口在订单上。</p>
     */
    private CompanionOrder requireOrderInService(Long orderId) {
        if (orderId == null) {
            throw new BusinessException(ResultCode.ORDER_NOT_FOUND);
        }
        CompanionOrder order = companionOrderMapper.selectById(orderId);
        if (order == null) {
            throw new BusinessException(ResultCode.ORDER_NOT_FOUND);
        }
        if (!SecurityUtils.currentUserId().equals(order.getCompanionId())) {
            throw new BusinessException(ResultCode.ORDER_NO_PERMISSION);
        }
        if (OrderStatus.of(order.getStatus()) != OrderStatus.IN_SERVICE) {
            throw new BusinessException(ResultCode.ORDER_STATUS_ILLEGAL,
                    "仅服务中的订单可以代录或撤回体征记录");
        }
        return order;
    }

    /**
     * 陪诊员读取链路的准入判断：订单存在 → 本单陪诊员 → 已进入服务。
     *
     * <p>与 {@link #requireOrderInService(Long)} 的唯一差别是状态边界：
     * 写入 / 撤回必须「服务中」，读取允许「服务中 / 已完成 / 已评价」。
     * 服务结束后陪诊员仍要能回看本单记录；未接单、已取消的订单一律拒绝。</p>
     */
    private CompanionOrder requireOrderReadable(Long orderId) {
        if (orderId == null) {
            throw new BusinessException(ResultCode.ORDER_NOT_FOUND);
        }
        CompanionOrder order = companionOrderMapper.selectById(orderId);
        if (order == null) {
            throw new BusinessException(ResultCode.ORDER_NOT_FOUND);
        }
        if (!SecurityUtils.currentUserId().equals(order.getCompanionId())) {
            throw new BusinessException(ResultCode.ORDER_NO_PERMISSION);
        }
        OrderStatus status = OrderStatus.of(order.getStatus());
        if (status != OrderStatus.IN_SERVICE
                && status != OrderStatus.COMPLETED
                && status != OrderStatus.REVIEWED) {
            throw new BusinessException(ResultCode.ORDER_STATUS_ILLEGAL,
                    "仅服务中、已完成或已评价的订单可以查看体征记录");
        }
        return order;
    }

    /* ================================================================== */
    /* 读取                                                                */
    /* ================================================================== */

    /**
     * 分页查询：{@code measured_at DESC, id DESC}，不重不漏。
     *
     * <p>{@code elderId} 与 {@code orderId} 二选一：家属 / 老人按老人档案取，
     * 陪诊员按订单取。陪诊员链路刻意<b>不</b>按 {@code elderId} 过滤 ——
     * 同一位老人可能有多单，按老人取会把别的订单代录的记录混进本单。</p>
     */
    private PageResult<VitalSignVO> pageOf(Long elderId, Long orderId, VitalSignQuery query) {
        DateRange range = query == null ? new DateRangeQuery().toRange() : query.toRange();
        VitalType type = parseOptionalType(query == null ? null : query.getVitalType());
        long pageNo = query == null || query.getPage() == null || query.getPage() < 1 ? 1L : query.getPage();
        long pageSize = query == null ? 10L : (query.getSize() == null || query.getSize() < 1
                ? 10L : Math.min(query.getSize(), 100L));

        Page<VitalSignRecord> page = new Page<>(pageNo, pageSize);
        vitalSignRecordMapper.selectPage(page, Wrappers.<VitalSignRecord>lambdaQuery()
                .eq(orderId == null, VitalSignRecord::getElderId, elderId)
                .eq(orderId != null, VitalSignRecord::getOrderId, orderId)
                .eq(type != null, VitalSignRecord::getVitalType, type == null ? null : type.name())
                .ge(VitalSignRecord::getMeasuredAt, range.startTime())
                .lt(VitalSignRecord::getMeasuredAt, range.endExclusive())
                .orderByDesc(VitalSignRecord::getMeasuredAt)
                .orderByDesc(VitalSignRecord::getId));
        return PageResult.of(page, VitalSignVO::of);
    }

    /**
     * 趋势：先按时间倒序多取一条判断截断，再裁剪并反转成升序。
     *
     * <p>倒序取「最近 500 条」而不是正序取「最早 500 条」：
     * 趋势图关心的是最近发生了什么，早期数据被截掉的损失最小。</p>
     */
    private VitalTrendVO trendOf(Long elderId, VitalTrendQuery query) {
        VitalType type = parseRequiredType(query == null ? null : query.getVitalType());
        DateRange range = query == null ? new DateRangeQuery().toRange() : query.toRange();

        List<VitalSignRecord> desc = vitalSignRecordMapper.selectList(Wrappers.<VitalSignRecord>lambdaQuery()
                .eq(VitalSignRecord::getElderId, elderId)
                .eq(VitalSignRecord::getVitalType, type.name())
                .ge(VitalSignRecord::getMeasuredAt, range.startTime())
                .lt(VitalSignRecord::getMeasuredAt, range.endExclusive())
                .orderByDesc(VitalSignRecord::getMeasuredAt)
                .orderByDesc(VitalSignRecord::getId)
                .last("LIMIT " + (MAX_TREND_POINTS + 1)));

        boolean truncated = desc.size() > MAX_TREND_POINTS;
        List<VitalSignRecord> recent = truncated
                ? new ArrayList<>(desc.subList(0, MAX_TREND_POINTS))
                : new ArrayList<>(desc);
        // 倒序拿到的「最近 N 条」反转成升序，与时间线共用同一比较器的镜像
        Collections.reverse(recent);

        List<VitalTrendPointVO> points = recent.stream().map(VitalTrendPointVO::of).toList();
        return new VitalTrendVO()
                .setVitalType(type.name())
                .setVitalTypeLabel(type.getLabel())
                .setUnit(type.getUnit())
                .setRange(VitalTrendVO.rangeOf(range))
                .setPointCount(points.size())
                .setTruncated(truncated)
                .setPoints(points);
    }

    /* ================================================================== */
    /* 写入与撤回                                                          */
    /* ================================================================== */

    /**
     * 落库并转换 VO；唯一键冲突转 {@code 2008}。
     *
     * <p>不在这里「先查有没有重复」：先查后插在并发下必然漏，
     * 唯一键才是最终防线。</p>
     */
    private VitalSignVO insert(VitalSignRecord record) {
        try {
            vitalSignRecordMapper.insert(record);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(ResultCode.VITAL_SIGN_DUPLICATED);
        }
        return VitalSignVO.of(record);
    }

    /** 取未撤回的记录；不存在或已撤回一律 {@code 2009} */
    private VitalSignRecord requireRecord(Long recordId) {
        if (recordId == null) {
            throw new BusinessException(ResultCode.VITAL_SIGN_NOT_FOUND);
        }
        VitalSignRecord record = vitalSignRecordMapper.selectById(recordId);
        if (record == null) {
            throw new BusinessException(ResultCode.VITAL_SIGN_NOT_FOUND);
        }
        return record;
    }

    /* ================================================================== */
    /* 入参校验                                                            */
    /* ================================================================== */

    /**
     * 按类型校验数值字段并组装实体。
     *
     * <p>三类字段<b>互斥</b>：只允许出现本类型的数值字段，多余字段一律 {@code 400}。
     * 这样既防止「一条记录混装两类数值」，也让时间线与趋势的展示口径保持唯一。</p>
     *
     * @param dto     录入入参
     * @param elderId 已校验归属的老人档案 ID
     * @return 待落库实体（来源 / 录入人 / 订单由调用方补）
     * @throws BusinessException 400 类型、数值、时间或字段组合不合法
     */
    private VitalSignRecord validatedRecord(VitalSignCreateDTO dto, Long elderId) {
        if (dto == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "请求体不能为空");
        }
        VitalType type = parseRequiredType(dto.getVitalType());
        LocalDateTime measuredAt = normalizeMeasuredAt(dto.getMeasuredAt());

        VitalSignRecord record = new VitalSignRecord();
        record.setElderId(elderId);
        record.setVitalType(type.name());
        record.setMeasuredAt(measuredAt);

        switch (type) {
            case BLOOD_PRESSURE -> {
                requireAbsent(dto.getGlucoseValue(), dto.getGlucoseContext(), dto.getWeightKg());
                if (dto.getSystolic() == null || dto.getDiastolic() == null) {
                    throw new BusinessException(ResultCode.PARAM_ERROR, "血压记录需要同时填写收缩压与舒张压");
                }
                int systolic = dto.getSystolic();
                int diastolic = dto.getDiastolic();
                if (systolic < MIN_SYSTOLIC || systolic > MAX_SYSTOLIC) {
                    throw new BusinessException(ResultCode.PARAM_ERROR,
                            "收缩压需为 " + MIN_SYSTOLIC + "–" + MAX_SYSTOLIC + " 之间的整数");
                }
                if (diastolic < MIN_DIASTOLIC || diastolic > MAX_DIASTOLIC) {
                    throw new BusinessException(ResultCode.PARAM_ERROR,
                            "舒张压需为 " + MIN_DIASTOLIC + "–" + MAX_DIASTOLIC + " 之间的整数");
                }
                if (diastolic >= systolic) {
                    throw new BusinessException(ResultCode.PARAM_ERROR, "舒张压需小于收缩压");
                }
                record.setSystolic(systolic);
                record.setDiastolic(diastolic);
            }
            case BLOOD_GLUCOSE -> {
                requireAbsent(dto.getSystolic(), dto.getDiastolic(), dto.getWeightKg());
                BigDecimal glucose = dto.getGlucoseValue();
                if (glucose == null) {
                    throw new BusinessException(ResultCode.PARAM_ERROR, "血糖记录需要填写血糖值");
                }
                GlucoseContext context = GlucoseContext.of(dto.getGlucoseContext());
                if (context == null) {
                    throw new BusinessException(ResultCode.PARAM_ERROR,
                            "血糖记录需要选择测量场景：FASTING / POSTPRANDIAL / RANDOM");
                }
                record.setGlucoseValue(decimalInRange(glucose, MIN_GLUCOSE, MAX_GLUCOSE, "血糖值"));
                record.setGlucoseContext(context.name());
            }
            case WEIGHT -> {
                requireAbsent(dto.getSystolic(), dto.getDiastolic(), dto.getGlucoseValue(),
                        dto.getGlucoseContext());
                BigDecimal weight = dto.getWeightKg();
                if (weight == null) {
                    throw new BusinessException(ResultCode.PARAM_ERROR, "体重记录需要填写体重");
                }
                record.setWeightKg(decimalInRange(weight, MIN_WEIGHT, MAX_WEIGHT, "体重"));
            }
            default -> throw new BusinessException(ResultCode.PARAM_ERROR, "体征类型取值不合法");
        }
        return record;
    }

    /**
     * 测量时间规范化：校验容差后截断到分钟。
     *
     * @param measuredAt 原始时间
     * @return 秒与纳秒都归零的时间
     */
    private LocalDateTime normalizeMeasuredAt(LocalDateTime measuredAt) {
        if (measuredAt == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "测量时间不能为空");
        }
        LocalDateTime normalized = measuredAt.withSecond(0).withNano(0);
        if (normalized.isAfter(LocalDateTime.now().plusMinutes(FUTURE_TOLERANCE_MINUTES))) {
            throw new BusinessException(ResultCode.PARAM_ERROR,
                    "测量时间不能晚于当前时间 " + FUTURE_TOLERANCE_MINUTES + " 分钟以上");
        }
        return normalized;
    }

    /**
     * 校验小数型数值：范围 + 最多 1 位小数，返回保留 1 位小数的值。
     *
     * @param value 原始数值
     * @param min   下限（含）
     * @param max   上限（含）
     * @param label 字段中文名，用于错误提示
     */
    private BigDecimal decimalInRange(BigDecimal value, BigDecimal min, BigDecimal max, String label) {
        BigDecimal stripped = value.stripTrailingZeros();
        if (stripped.scale() > 1) {
            throw new BusinessException(ResultCode.PARAM_ERROR, label + "最多保留 1 位小数");
        }
        if (value.compareTo(min) < 0 || value.compareTo(max) > 0) {
            throw new BusinessException(ResultCode.PARAM_ERROR,
                    label + "需为 " + min.toPlainString() + "–" + max.toPlainString() + " 之间的数值");
        }
        return value.setScale(1, RoundingMode.HALF_UP);
    }

    /** 互斥字段：本类型用不到的字段必须为空 */
    private void requireAbsent(Object... values) {
        for (Object value : values) {
            if (value != null) {
                throw new BusinessException(ResultCode.PARAM_ERROR, "记录中出现了当前类型不使用的字段");
            }
        }
    }

    /** 解析必填类型；缺失或非法一律 400 */
    private VitalType parseRequiredType(String raw) {
        VitalType type = VitalType.of(raw);
        if (type == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR,
                    "体征类型取值不合法，应为 BLOOD_PRESSURE / BLOOD_GLUCOSE / WEIGHT");
        }
        return type;
    }

    /** 解析可选的类型过滤；空值返回 {@code null}（表示全部） */
    private VitalType parseOptionalType(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return parseRequiredType(raw);
    }

    /** 老人本人：档案 ID 由登录态推导，未建档报 2001 */
    private Long requireSelfElderId() {
        SysUser user = sysUserMapper.selectById(SecurityUtils.currentUserId());
        if (user == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        Long elderId = elderService.elderIdOf(user);
        if (elderId == null) {
            throw new BusinessException(ResultCode.ELDER_NOT_FOUND, "尚未建立老人档案");
        }
        return elderId;
    }
}

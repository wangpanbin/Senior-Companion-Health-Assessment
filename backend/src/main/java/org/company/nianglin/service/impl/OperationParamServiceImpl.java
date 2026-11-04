package org.company.nianglin.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.company.nianglin.common.PageResult;
import org.company.nianglin.common.ResultCode;
import org.company.nianglin.config.FulfillmentProperties;
import org.company.nianglin.config.SmsProperties;
import org.company.nianglin.constant.OperTargetType;
import org.company.nianglin.constant.OperType;
import org.company.nianglin.constant.OperationParam;
import org.company.nianglin.dto.OperationParamUpdateDTO;
import org.company.nianglin.entity.AdminOperLog;
import org.company.nianglin.entity.SysDict;
import org.company.nianglin.exception.BusinessException;
import org.company.nianglin.mapper.AdminOperLogMapper;
import org.company.nianglin.mapper.SysDictMapper;
import org.company.nianglin.service.OperationParamService;
import org.company.nianglin.service.support.OperLogRecorder;
import org.company.nianglin.service.support.OperationParamCache;
import org.company.nianglin.vo.OperLogVO;
import org.company.nianglin.vo.OperationParamVO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 运营参数实现（V4 · P2-3）。
 *
 * <h3>读取路径绝不抛异常</h3>
 *
 * <p>参数读取挂在漏服扫描、催单、导出、短信频控等主流程上。
 * 任何一次读库失败若向上抛，都会把「调参」变成「停服」——
 * 因此这里的读方法统一「命中 → 过期重载 → 上一份快照 → application.yml 默认值」
 * 四级回落，最坏情况仍按启动时的默认值继续跑。</p>
 *
 * <h3>缓存失效时机</h3>
 *
 * <p>成功修改后注册 {@link TransactionSynchronization#afterCommit()} 再刷新缓存：
 * 事务回滚时缓存保持旧值，避免「接口报错但内存里已经是新值」。
 * 其他实例依赖 TTL（默认 60s）收敛。</p>
 *
 * @author 银龄伴诊团队
 * @since V4 · P2-3
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OperationParamServiceImpl implements OperationParamService {

    /** 运营参数在 sys_dict 中的类型；与下拉字典（HOSPITAL 等）隔离 */
    static final String DICT_TYPE = "OPS_PARAM";

    static final int STATUS_ENABLED = 1;

    private final SysDictMapper sysDictMapper;
    private final AdminOperLogMapper operLogMapper;
    private final OperLogRecorder operLogRecorder;
    private final OperationParamCache cache;
    private final FulfillmentProperties fulfillmentProperties;
    private final SmsProperties smsProperties;

    /** 同步重载锁，防止缓存过期瞬间多线程同时回源 */
    private final Object reloadLock = new Object();

    /** 默认值仍以 application.yml 为唯一来源，不在 Java 里再存一份 */
    @Value("${nianglin.medication.missed-threshold-minutes:60}")
    private int defaultMissedThresholdMinutes;

    @Value("${nianglin.export.max-rows:10000}")
    private int defaultExportMaxRows;

    /* ================================================================== */
    /* 读取                                                                */
    /* ================================================================== */

    @Override
    public int getInt(OperationParam param) {
        String raw = cache.get(param.getCode());
        if (raw == null) {
            raw = reload(param);
        }
        return parseInt(param, raw);
    }

    /**
     * 回源读取：缓存过期或首次访问时调用。
     *
     * <p>读库失败时返回上一份快照里的值（可能已过期但仍是最近一次成功值），
     * 快照也没有时返回 {@code null}，由 {@link #parseInt} 回落默认值。</p>
     */
    private String reload(OperationParam param) {
        Map<String, String> previous = cache.snapshot();
        synchronized (reloadLock) {
            // 双重检查：等锁期间别的线程可能已经重载完成
            String cached = cache.get(param.getCode());
            if (cached != null) {
                return cached;
            }
            try {
                Map<String, String> loaded = loadSnapshot();
                cache.putAll(loaded);
                return loaded.get(param.getCode());
            } catch (Exception e) {
                log.warn("运营参数读取失败，沿用上一份快照/默认值 | code={} | {}",
                        param.getCode(), e.toString());
                return previous.get(param.getCode());
            }
        }
    }

    private Map<String, String> loadSnapshot() {
        List<SysDict> rows = sysDictMapper.selectList(Wrappers.<SysDict>lambdaQuery()
                .eq(SysDict::getDictType, DICT_TYPE)
                .eq(SysDict::getStatus, STATUS_ENABLED));
        Map<String, String> values = new HashMap<>();
        for (SysDict row : rows) {
            if (row.getDictCode() != null && row.getDictValue() != null) {
                values.put(row.getDictCode(), row.getDictValue());
            }
        }
        return values;
    }

    /** 解析失败或越界一律记 warn 并回落默认值，不让脏数据进入业务计算 */
    private int parseInt(OperationParam param, String raw) {
        int fallback = defaultValueOf(param);
        if (!StringUtils.hasText(raw)) {
            return fallback;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            if (value < param.getMin() || value > param.getMax()) {
                log.warn("运营参数越界，回落默认值 | code={} value={} range=[{},{}] default={}",
                        param.getCode(), value, param.getMin(), param.getMax(), fallback);
                return fallback;
            }
            return value;
        } catch (NumberFormatException e) {
            log.warn("运营参数格式非法，回落默认值 | code={} value={} default={}",
                    param.getCode(), raw, fallback);
            return fallback;
        }
    }

    private int defaultValueOf(OperationParam param) {
        return switch (param) {
            case MEDICATION_MISSED_THRESHOLD_MINUTES -> defaultMissedThresholdMinutes;
            case URGE_COOLDOWN_MINUTES -> fulfillmentProperties.getUrgeCooldownMinutes();
            case EXPORT_MAX_ROWS -> defaultExportMaxRows;
            case SMS_MISSED_DOSE_DAILY_CAP -> smsProperties.getFrequency().getMissedDoseDailyCap();
        };
    }

    /* ================================================================== */
    /* 管理端                                                              */
    /* ================================================================== */

    @Override
    public List<OperationParamVO> list() {
        List<SysDict> rows;
        boolean dbFailed = false;
        try {
            rows = sysDictMapper.selectList(Wrappers.<SysDict>lambdaQuery()
                    .eq(SysDict::getDictType, DICT_TYPE)
                    .orderByAsc(SysDict::getSortNo));
        } catch (Exception e) {
            dbFailed = true;
            rows = Collections.emptyList();
            log.warn("运营参数列表读取失败，返回默认值 | {}", e.toString());
        }

        Map<String, SysDict> byCode = new HashMap<>();
        for (SysDict row : rows) {
            if (row.getDictCode() != null) {
                byCode.put(row.getDictCode(), row);
            }
        }

        List<OperationParamVO> result = new ArrayList<>(OperationParam.values().length);
        for (OperationParam param : OperationParam.values()) {
            SysDict row = byCode.get(param.getCode());
            boolean enabled = row != null && Objects.equals(row.getStatus(), STATUS_ENABLED);
            int defaultValue = defaultValueOf(param);
            int value = enabled ? parseInt(param, row.getDictValue()) : defaultValue;
            result.add(new OperationParamVO()
                    .setCode(param.getCode())
                    .setLabel(param.getLabel())
                    .setValue(value)
                    .setDefaultValue(defaultValue)
                    .setMin(param.getMin())
                    .setMax(param.getMax())
                    .setUnit(param.getUnit())
                    .setDescription(param.getDescription())
                    .setStatus(row == null ? null : row.getStatus())
                    .setOverridden(enabled && value != defaultValue)
                    .setUsingDefault(dbFailed || !enabled)
                    .setUpdateTime(row == null ? null : row.getUpdateTime()));
        }
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(String code, OperationParamUpdateDTO dto) {
        OperationParam param = OperationParam.ofCode(code);
        if (param == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "不支持的参数键：" + code);
        }
        if (dto == null || dto.getValue() == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "参数值不能为空");
        }
        int newValue = dto.getValue();
        if (newValue < param.getMin() || newValue > param.getMax()) {
            throw new BusinessException(ResultCode.PARAM_ERROR,
                    "参数 " + code + " 必须在 [" + param.getMin() + ", " + param.getMax() + "] 内");
        }

        SysDict row = sysDictMapper.selectOne(Wrappers.<SysDict>lambdaQuery()
                .eq(SysDict::getDictType, DICT_TYPE)
                .eq(SysDict::getDictCode, code));
        String oldValue = row == null ? null : row.getDictValue();

        if (row == null) {
            row = new SysDict();
            row.setDictType(DICT_TYPE);
            row.setDictCode(code);
            row.setDictLabel(param.getLabel());
            row.setDictValue(String.valueOf(newValue));
            row.setSortNo(sortNoOf(param));
            row.setStatus(STATUS_ENABLED);
            row.setRemark(remarkOf(param, dto.getRemark()));
            sysDictMapper.insert(row);
        } else {
            row.setDictValue(String.valueOf(newValue));
            row.setStatus(STATUS_ENABLED);
            if (StringUtils.hasText(dto.getRemark())) {
                row.setRemark(dto.getRemark().trim());
            }
            sysDictMapper.updateById(row);
        }

        operLogRecorder.record(OperType.UPDATE_OPERATION_PARAM, OperTargetType.OPERATION_PARAM,
                row.getId(), code, oldValue, String.valueOf(newValue),
                "键=" + code + "；旧值=" + (oldValue == null ? "(未设置)" : oldValue)
                        + "；新值=" + newValue
                        + (StringUtils.hasText(dto.getRemark()) ? "；备注=" + dto.getRemark().trim() : ""));

        refreshCacheAfterCommit();
    }

    @Override
    public PageResult<OperLogVO> logs(long page, long size) {
        Page<AdminOperLog> mpPage = new Page<>(page, size);
        Page<AdminOperLog> result = operLogMapper.selectPage(mpPage,
                Wrappers.<AdminOperLog>lambdaQuery()
                        .eq(AdminOperLog::getOperType, OperType.UPDATE_OPERATION_PARAM.name())
                        .orderByDesc(AdminOperLog::getOperTime)
                        .orderByDesc(AdminOperLog::getId));
        return PageResult.of(result, OperLogVO::of);
    }

    /* ================================================================== */
    /* 辅助                                                                */
    /* ================================================================== */

    /**
     * 事务提交后再刷新缓存；回滚时保持旧快照。
     * 单测没有事务上下文时立即刷新，便于断言「改完即生效」。
     */
    private void refreshCacheAfterCommit() {
        Runnable refresh = () -> {
            try {
                cache.putAll(loadSnapshot());
            } catch (Exception e) {
                cache.invalidate();
                log.warn("运营参数缓存刷新失败，下次读取回源 | {}", e.toString());
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    refresh.run();
                }
            });
        } else {
            refresh.run();
        }
    }

    private static int sortNoOf(OperationParam param) {
        return param.ordinal() + 1;
    }

    private static String remarkOf(OperationParam param, String userRemark) {
        String base = param.getDescription() + "；可调范围 [" + param.getMin() + ", " + param.getMax() + "] " + param.getUnit();
        return StringUtils.hasText(userRemark) ? base + "；" + userRemark.trim() : base;
    }
}

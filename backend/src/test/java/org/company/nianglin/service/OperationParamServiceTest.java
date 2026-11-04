package org.company.nianglin.service;

import org.company.nianglin.common.ResultCode;
import org.company.nianglin.config.FulfillmentProperties;
import org.company.nianglin.config.SmsProperties;
import org.company.nianglin.constant.OperTargetType;
import org.company.nianglin.constant.OperType;
import org.company.nianglin.constant.OperationParam;
import org.company.nianglin.dto.OperationParamUpdateDTO;
import org.company.nianglin.entity.SysDict;
import org.company.nianglin.exception.BusinessException;
import org.company.nianglin.mapper.AdminOperLogMapper;
import org.company.nianglin.mapper.SysDictMapper;
import org.company.nianglin.service.impl.OperationParamServiceImpl;
import org.company.nianglin.service.support.OperLogRecorder;
import org.company.nianglin.service.support.OperationParamCache;
import org.company.nianglin.vo.OperationParamVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 运营参数服务：缓存命中 / 失效、默认回落、留痕、越界校验。
 *
 * <p>这里用真缓存 + mock mapper：缓存行为本身就是被测对象，
 * 换成 mock 缓存会让「第二次读不查库」这类断言失去意义。</p>
 *
 * @author 银龄伴诊团队
 * @since V4 · P2-3
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("运营参数服务：缓存 / 回落 / 留痕 / 校验")
class OperationParamServiceTest {

    private static final String MEDICATION_CODE = OperationParam.MEDICATION_MISSED_THRESHOLD_MINUTES.getCode();

    @Mock
    private SysDictMapper sysDictMapper;

    @Mock
    private AdminOperLogMapper operLogMapper;

    @Mock
    private OperLogRecorder operLogRecorder;

    private OperationParamCache cache;
    private FulfillmentProperties fulfillmentProperties;
    private SmsProperties smsProperties;
    private OperationParamServiceImpl service;

    @BeforeEach
    void setUp() {
        cache = new OperationParamCache(60_000L);
        fulfillmentProperties = new FulfillmentProperties();
        smsProperties = new SmsProperties();
        service = new OperationParamServiceImpl(sysDictMapper, operLogMapper, operLogRecorder,
                cache, fulfillmentProperties, smsProperties);
        ReflectionTestUtils.setField(service, "defaultMissedThresholdMinutes", 60);
        ReflectionTestUtils.setField(service, "defaultExportMaxRows", 10000);
    }

    /* ================================================================== */
    /* 缓存命中 / 失效                                                      */
    /* ================================================================== */

    @Test
    @DisplayName("缓存命中：连续两次读取只查一次库")
    void shouldQueryDbOnlyOnceWithinTtl() {
        when(sysDictMapper.selectList(any())).thenReturn(List.of(row(MEDICATION_CODE, "45", 1)));

        assertThat(service.getInt(OperationParam.MEDICATION_MISSED_THRESHOLD_MINUTES)).isEqualTo(45);
        assertThat(service.getInt(OperationParam.MEDICATION_MISSED_THRESHOLD_MINUTES)).isEqualTo(45);

        verify(sysDictMapper, times(1)).selectList(any());
    }

    @Test
    @DisplayName("修改成功：事务提交后刷新缓存，下一次读取立即拿到新值")
    void updateShouldInvalidateCacheAndTakeEffect() {
        SysDict existing = row(MEDICATION_CODE, "60", 1);
        existing.setId(10L);
        when(sysDictMapper.selectOne(any())).thenReturn(existing);
        when(sysDictMapper.selectList(any())).thenReturn(List.of(row(MEDICATION_CODE, "45", 1)));

        OperationParamUpdateDTO dto = new OperationParamUpdateDTO();
        dto.setValue(45);
        dto.setRemark("夜间漏服较多");

        service.update(MEDICATION_CODE, dto);

        // 更新后读取来自刷新后的快照，不再额外查库
        assertThat(service.getInt(OperationParam.MEDICATION_MISSED_THRESHOLD_MINUTES)).isEqualTo(45);
        verify(sysDictMapper, times(1)).selectList(any());
        verify(sysDictMapper).updateById(any(SysDict.class));

        // 留痕：键、旧值、新值、操作人（操作人由 OperLogRecorder 内部解析）
        verify(operLogRecorder).record(
                eq(OperType.UPDATE_OPERATION_PARAM),
                eq(OperTargetType.OPERATION_PARAM),
                eq(10L),
                eq(MEDICATION_CODE),
                eq("60"),
                eq("45"),
                contains("键=" + MEDICATION_CODE));
    }

    /* ================================================================== */
    /* 默认回落                                                            */
    /* ================================================================== */

    @Test
    @DisplayName("读库失败：回落 application.yml 默认值，不抛异常")
    void shouldFallBackToDefaultWhenDbFails() {
        when(sysDictMapper.selectList(any())).thenThrow(new RuntimeException("db down"));

        assertThat(service.getInt(OperationParam.MEDICATION_MISSED_THRESHOLD_MINUTES)).isEqualTo(60);
        assertThat(service.getInt(OperationParam.URGE_COOLDOWN_MINUTES)).isEqualTo(10);
        assertThat(service.getInt(OperationParam.EXPORT_MAX_ROWS)).isEqualTo(10000);
        assertThat(service.getInt(OperationParam.SMS_MISSED_DOSE_DAILY_CAP)).isEqualTo(3);
    }

    @Test
    @DisplayName("字典缺行：回落默认值")
    void shouldFallBackWhenKeyMissing() {
        when(sysDictMapper.selectList(any())).thenReturn(Collections.emptyList());

        assertThat(service.getInt(OperationParam.MEDICATION_MISSED_THRESHOLD_MINUTES)).isEqualTo(60);
    }

    @Test
    @DisplayName("字典值非法（非数字 / 越界 / 空串）：回落默认值并继续服务")
    void shouldFallBackWhenValueInvalid() {
        when(sysDictMapper.selectList(any())).thenReturn(List.of(row(MEDICATION_CODE, "abc", 1)));
        assertThat(service.getInt(OperationParam.MEDICATION_MISSED_THRESHOLD_MINUTES)).isEqualTo(60);

        cache.invalidate();
        when(sysDictMapper.selectList(any())).thenReturn(List.of(row(MEDICATION_CODE, "0", 1)));
        assertThat(service.getInt(OperationParam.MEDICATION_MISSED_THRESHOLD_MINUTES)).isEqualTo(60);

        cache.invalidate();
        when(sysDictMapper.selectList(any())).thenReturn(List.of(row(MEDICATION_CODE, "  ", 1)));
        assertThat(service.getInt(OperationParam.MEDICATION_MISSED_THRESHOLD_MINUTES)).isEqualTo(60);
    }

    @Test
    @DisplayName("列表读库失败：仍返回 4 项默认值并标记 usingDefault")
    void listShouldExposeDefaultsWhenDbFails() {
        when(sysDictMapper.selectList(any())).thenThrow(new RuntimeException("db down"));

        List<OperationParamVO> params = service.list();

        assertThat(params).hasSize(4);
        assertThat(params).allMatch(OperationParamVO::getUsingDefault);
        assertThat(params)
                .extracting(OperationParamVO::getValue)
                .containsExactly(60, 10, 10000, 3);
    }

    /* ================================================================== */
    /* 修改校验                                                            */
    /* ================================================================== */

    @Test
    @DisplayName("修改未知键：400，不落库不留痕")
    void updateShouldRejectUnknownCode() {
        OperationParamUpdateDTO dto = new OperationParamUpdateDTO();
        dto.setValue(10);

        assertThatThrownBy(() -> service.update("unknown.key", dto))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                        .isEqualTo(ResultCode.PARAM_ERROR.getCode()));

        verify(sysDictMapper, never()).updateById(any(SysDict.class));
        verify(operLogRecorder, never()).record(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("修改越界值：400，不落库不留痕")
    void updateShouldRejectOutOfRangeValue() {
        OperationParamUpdateDTO dto = new OperationParamUpdateDTO();
        dto.setValue(0);

        assertThatThrownBy(() -> service.update(MEDICATION_CODE, dto))
                .isInstanceOf(BusinessException.class);

        verify(sysDictMapper, never()).updateById(any(SysDict.class));
        verify(operLogRecorder, never()).record(any(), any(), any(), any(), any(), any(), any());
    }

    /* ================================================================== */

    private static SysDict row(String code, String value, int status) {
        SysDict row = new SysDict();
        row.setId(1L);
        row.setDictType("OPS_PARAM");
        row.setDictCode(code);
        row.setDictValue(value);
        row.setStatus(status);
        return row;
    }
}

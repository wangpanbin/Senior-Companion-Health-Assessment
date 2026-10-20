package org.company.nianglin.service;

import org.company.nianglin.common.DateRange;
import org.company.nianglin.config.OperationsProperties;
import org.company.nianglin.dto.OperationsEffectQuery;
import org.company.nianglin.exception.BusinessException;
import org.company.nianglin.mapper.OperationsMapper;
import org.company.nianglin.service.impl.OperationsServiceImpl;
import org.company.nianglin.service.support.metrics.MetricCalculator;
import org.company.nianglin.service.support.metrics.MetricCalculatorRegistry;
import org.company.nianglin.vo.OperationsEffectVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 干预效果对比的口径单测（V3.6 · T5 · T7）。
 *
 * <h3>这个测试在防什么</h3>
 *
 * <ol>
 *   <li><b>两侧不是同一条口径</b> —— 对比出来的差值可能只是两个公式的差异；</li>
 *   <li><b>未注册的指标被「猜」一个算法算出来</b> —— 那等于凭空造数；</li>
 *   <li><b>前一期区间长度与后一期不等</b> —— 差值里混进了区间长度这个因素；</li>
 *   <li><b>区间不合法时没报错</b> —— 起止颠倒或超上限应当是 9002。</li>
 * </ol>
 *
 * @author 银龄伴诊团队
 * @since V3.6
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("V3.6 干预效果对比：两侧同口径 / 未注册即 400 / 区间非法 9002")
class OperationsEffectTest {

    private static final String METRIC = "operations.fulfillment_rate";

    @Mock
    private OperationsMapper mapper;

    @Mock
    private AdminService adminService;

    private final OperationsProperties properties = new OperationsProperties();

    private OperationsServiceImpl service;

    @BeforeEach
    void setUp() {
        // 默认用「没有任何指标注册」的注册表：未注册的指标必须被拒
        service = new OperationsServiceImpl(mapper, adminService, properties,
                new MetricCalculatorRegistry(List.of()));
    }

    /* ================================================================== */
    /* 1 · 两侧同口径                                                      */
    /* ================================================================== */

    @Test
    @DisplayName("两期由同一个计算器算两遍（不是两边各写一遍公式）")
    void bothPeriodsShouldUseTheSameCalculator() {
        RecordingCalculator calculator = new RecordingCalculator(METRIC, range -> "96.00%");
        OperationsEffectVO vo = serviceWith(calculator).effect(query("2026-10-01", "2026-10-15"));

        assertEquals(2, calculator.seen.size(), "同一个计算器应该被调用两次（各一期）");
        assertEquals(LocalDate.parse("2026-10-01"), calculator.seen.get(0).start());
        assertEquals(LocalDate.parse("2026-10-15"), calculator.seen.get(0).end());
        assertEquals(LocalDate.parse("2026-09-16"), calculator.seen.get(1).start());
        assertEquals(LocalDate.parse("2026-09-30"), calculator.seen.get(1).end());
        assertEquals("96.00%", vo.getCurrent().getValue());
        assertEquals("96.00%", vo.getPrevious().getValue());
    }

    @Test
    @DisplayName("指标中文名取自指标字典（不另写一份名表）")
    void metricNameShouldComeFromDictionary() {
        RecordingCalculator calculator = new RecordingCalculator(METRIC, range -> "96.00%");

        OperationsEffectVO vo = serviceWith(calculator).effect(query("2026-10-01", "2026-10-15"));

        assertEquals("履约率", vo.getMetricName());
        assertEquals(METRIC, vo.getMetric());
    }

    /* ================================================================== */
    /* 2 · 前一期区间的自动推导                                            */
    /* ================================================================== */

    @Test
    @DisplayName("compareFrom/compareTo 缺省时，自动取「与后一期等长、紧邻其前」的区间")
    void previousPeriodShouldBeDerivedWhenMissing() {
        RecordingCalculator calculator = new RecordingCalculator(METRIC, range -> "96.00%");

        OperationsEffectVO vo = serviceWith(calculator).effect(query("2026-10-01", "2026-10-15"));

        // 后一期 15 天 → 前一期也是 15 天，且紧邻其前
        assertEquals("2026-10-01", vo.getCurrent().getFrom());
        assertEquals("2026-10-15", vo.getCurrent().getTo());
        assertEquals("2026-09-16", vo.getPrevious().getFrom());
        assertEquals("2026-09-30", vo.getPrevious().getTo());
        assertEquals(15, new DateRange(LocalDate.parse(vo.getPrevious().getFrom()),
                LocalDate.parse(vo.getPrevious().getTo())).days(),
                "前一期长度必须与后一期相等");
    }

    @Test
    @DisplayName("显式给出 compareFrom/compareTo 时，用给定的区间（不自动推导）")
    void explicitPreviousPeriodShouldBeHonoured() {
        RecordingCalculator calculator = new RecordingCalculator(METRIC, range -> "96.00%");
        OperationsEffectQuery query = query("2026-10-01", "2026-10-15");
        query.setCompareFrom("2026-08-01");
        query.setCompareTo("2026-08-07");

        OperationsEffectVO vo = serviceWith(calculator).effect(query);

        assertEquals("2026-08-01", vo.getPrevious().getFrom());
        assertEquals("2026-08-07", vo.getPrevious().getTo());
    }

    @Test
    @DisplayName("只给了对比区间的一端 → 400（不许猜另一端）")
    void halfGivenPreviousPeriodShouldBeRejected() {
        RecordingCalculator calculator = new RecordingCalculator(METRIC, range -> "96.00%");
        OperationsEffectQuery query = query("2026-10-01", "2026-10-15");
        query.setCompareFrom("2026-09-16");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> serviceWith(calculator).effect(query));

        assertEquals(400, ex.getCode());
    }

    /* ================================================================== */
    /* 3 · 未注册的指标                                                    */
    /* ================================================================== */

    @Test
    @DisplayName("未注册的指标 → 400，且不调用任何计算器")
    void unregisteredMetricShouldBeRejected() {
        RecordingCalculator calculator = new RecordingCalculator(METRIC, range -> "96.00%");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> serviceWith(calculator).effect(query("operations.not_registered", "2026-10-01", "2026-10-15")));

        assertEquals(400, ex.getCode());
        assertTrue(calculator.seen.isEmpty(), "未注册的指标不该触发任何计算");
    }

    @Test
    @DisplayName("指标为空 → 400")
    void blankMetricShouldBeRejected() {
        OperationsEffectQuery query = new OperationsEffectQuery();
        query.setMetric("  ");

        BusinessException ex = assertThrows(BusinessException.class, () -> service.effect(query));

        assertEquals(400, ex.getCode());
    }

    /* ================================================================== */
    /* 4 · 区间合法性                                                      */
    /* ================================================================== */

    @Test
    @DisplayName("后一期起止颠倒 → 9002")
    void reversedCurrentPeriodShouldBeRejected() {
        RecordingCalculator calculator = new RecordingCalculator(METRIC, range -> "96.00%");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> serviceWith(calculator).effect(query("2026-10-15", "2026-10-01")));

        assertEquals(9002, ex.getCode());
    }

    @Test
    @DisplayName("后一期超过 366 天 → 9002")
    void tooLongCurrentPeriodShouldBeRejected() {
        RecordingCalculator calculator = new RecordingCalculator(METRIC, range -> "96.00%");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> serviceWith(calculator).effect(query("2024-01-01", "2026-10-20")));

        assertEquals(9002, ex.getCode());
    }

    @Test
    @DisplayName("前一期起止颠倒 → 9002")
    void reversedPreviousPeriodShouldBeRejected() {
        RecordingCalculator calculator = new RecordingCalculator(METRIC, range -> "96.00%");
        OperationsEffectQuery query = query("2026-10-01", "2026-10-15");
        query.setCompareFrom("2026-09-30");
        query.setCompareTo("2026-09-16");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> serviceWith(calculator).effect(query));

        assertEquals(9002, ex.getCode());
    }

    /* ================================================================== */
    /* 5 · 差值                                                            */
    /* ================================================================== */

    @Test
    @DisplayName("百分比指标的差值是百分点（+4.30%）")
    void percentDeltaShouldBePercentagePoints() {
        RecordingCalculator calculator = new RecordingCalculator(METRIC, range ->
                range.start().equals(LocalDate.parse("2026-10-01")) ? "96.40%" : "92.10%");

        OperationsEffectVO vo = serviceWith(calculator).effect(query("2026-10-01", "2026-10-15"));

        assertEquals("96.40%", vo.getCurrent().getValue());
        assertEquals("92.10%", vo.getPrevious().getValue());
        assertEquals("+4.30%", vo.getDelta());
    }

    @Test
    @DisplayName("计数指标的差值不带百分号（+2）")
    void countDeltaShouldNotHavePercentSign() {
        RecordingCalculator calculator = new RecordingCalculator("operations.completed_order_count", range ->
                range.start().equals(LocalDate.parse("2026-10-01")) ? "12" : "10");

        OperationsEffectVO vo = serviceWith(calculator)
                .effect(query("operations.completed_order_count", "2026-10-01", "2026-10-15"));

        assertEquals("+2", vo.getDelta());
    }

    @Test
    @DisplayName("指标变差时差值带负号（-1.20%）")
    void negativeDeltaShouldKeepSign() {
        RecordingCalculator calculator = new RecordingCalculator(METRIC, range ->
                range.start().equals(LocalDate.parse("2026-10-01")) ? "90.00%" : "91.20%");

        OperationsEffectVO vo = serviceWith(calculator).effect(query("2026-10-01", "2026-10-15"));

        assertEquals("-1.20%", vo.getDelta());
    }

    @Test
    @DisplayName("两侧格式不同（% vs 计数）→ 差值返回「—」，不硬凑一个数")
    void mismatchedFormatsShouldReturnDash() {
        RecordingCalculator calculator = new RecordingCalculator(METRIC, range ->
                range.start().equals(LocalDate.parse("2026-10-01")) ? "96.40%" : "10");

        OperationsEffectVO vo = serviceWith(calculator).effect(query("2026-10-01", "2026-10-15"));

        assertEquals("—", vo.getDelta());
    }

    @Test
    @DisplayName("响应结构完整（metric / metricName / current / previous / delta）")
    void responseShouldCarryAllParts() {
        RecordingCalculator calculator = new RecordingCalculator(METRIC, range -> "96.00%");

        OperationsEffectVO vo = serviceWith(calculator).effect(query("2026-10-01", "2026-10-15"));

        assertNotNull(vo.getMetric());
        assertNotNull(vo.getMetricName());
        assertNotNull(vo.getCurrent());
        assertNotNull(vo.getCurrent().getFrom());
        assertNotNull(vo.getCurrent().getTo());
        assertNotNull(vo.getCurrent().getValue());
        assertNotNull(vo.getPrevious());
        assertNotNull(vo.getPrevious().getFrom());
        assertNotNull(vo.getPrevious().getTo());
        assertNotNull(vo.getPrevious().getValue());
        assertNotNull(vo.getDelta());
    }

    /* ================================================================== */
    /* 工具                                                                */
    /* ================================================================== */

    /** 用给定的计算器装配一个 Service */
    private OperationsServiceImpl serviceWith(MetricCalculator... calculators) {
        return new OperationsServiceImpl(mapper, adminService, properties,
                new MetricCalculatorRegistry(List.of(calculators)));
    }

    private static OperationsEffectQuery query(String from, String to) {
        return query(METRIC, from, to);
    }

    private static OperationsEffectQuery query(String metric, String from, String to) {
        OperationsEffectQuery query = new OperationsEffectQuery();
        query.setMetric(metric);
        query.setFrom(from);
        query.setTo(to);
        return query;
    }

    /** 记录自己被调用过哪些区间的计算器（用来证明「两期同一条口径」） */
    private static final class RecordingCalculator implements MetricCalculator {
        private final String key;
        private final Function<DateRange, String> value;
        private final List<DateRange> seen = new ArrayList<>();

        private RecordingCalculator(String key, Function<DateRange, String> value) {
            this.key = key;
            this.value = value;
        }

        @Override
        public String metricKey() {
            return key;
        }

        @Override
        public String calculate(DateRange range) {
            seen.add(range);
            return value.apply(range);
        }
    }
}

package org.company.nianglin.service.support.matching;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

/**
 * 候选人快照（V3.4 · FR-Rule）。
 *
 * <h3>为什么是快照而不是实体</h3>
 *
 * <p>规则必须是<b>无 Spring 上下文的纯函数</b>：给它一份快照，它算出一个分。
 * 如果规则直接去查数据库，它就没法被毫秒级地反复测试边界值 ——
 * 而「每个阈值的边界」正是这个引擎最需要被锁住的东西。</p>
 *
 * <h3>字段为什么要「该有的都有」</h3>
 *
 * <p>每个维度可能因为<b>数据缺失</b>而降级（没坐标、没时段、没区域码）。
 * 快照里对应字段就是 {@code null}，规则据此记 0 并在
 * {@link DimensionResult#note()} 里写明原因 —— <b>绝不推断</b>。</p>
 *
 * @param companionId      陪诊员用户 ID
 * @param regionCode       服务区域码（{@code <CITY>_<DISTRICT>}）；无则 {@code null}
 * @param serviceArea      服务区域自由文本（结构化列缺失时的回退匹配依据）
 * @param longitude        陪诊员档案经度；无坐标时 {@code null}
 * @param latitude         陪诊员档案纬度；无坐标时 {@code null}
 * @param slots            可服务时段；未登记时为空列表
 * @param departmentCounts 该陪诊员在各科室的已完成单数（{@code department -> 单数}）
 * @param rating           平均评分（0.00–5.00）
 * @author 银龄伴诊团队
 * @since V3.4
 */
public record MatchingCandidate(
        Long companionId,
        String regionCode,
        String serviceArea,
        BigDecimal longitude,
        BigDecimal latitude,
        List<AvailableSlot> slots,
        Map<String, Integer> departmentCounts,
        BigDecimal rating) {

    /** 可服务时段：周内第几天（1=周一 … 7=周日）+ 起止时刻 */
    public record AvailableSlot(int weekday, LocalTime start, LocalTime end) {
    }

    /** 陪诊员有没有坐标 */
    public boolean hasCoordinates() {
        return longitude != null && latitude != null;
    }

    /** 陪诊员有没有登记区域码 */
    public boolean hasRegionCode() {
        return regionCode != null && !regionCode.isBlank();
    }
}

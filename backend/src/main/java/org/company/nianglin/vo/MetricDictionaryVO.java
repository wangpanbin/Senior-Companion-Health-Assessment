package org.company.nianglin.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.ArrayList;
import java.util.List;

/**
 * 指标字典的只读导出（V3.6 · T7 · FR-Trace）。
 *
 * <h3>「字典的字典」</h3>
 *
 * <p>本 VO 是指标字典机器面（{@code metrics/metric-dictionary.yml}）的对外投影：
 * 字典版本 + 全部指标定义。运营用它对账（看板上的数对不对得上定义），
 * 开发用它排查（某个 key 到底登记了没）。</p>
 *
 * <p>⚠️ 本 VO 的字段<b>全是结构字段</b>，一个指标值都没有 ——
 * 它导出的是「口径」，不是「值」。所以它不进
 * {@code metrics/endpoints.yml}（那张表登记的是「哪个端点返回哪些指标<b>值</b>」），
 * 也不在指标字典的 {@code bindings} 里。</p>
 *
 * @author 银龄伴诊团队
 * @since V3.6
 */
@Data
@Accessors(chain = true)
@Schema(description = "指标字典的只读导出（版本 + 全部指标定义）")
public class MetricDictionaryVO {

    @Schema(description = "字典版本（机器面 version 字段）", example = "4")
    private Integer version;

    @Schema(description = "登记的指标条数", example = "64")
    private Integer total;

    @Schema(description = "全部指标定义（按字典里的登记顺序）")
    private List<MetricDefinitionVO> metrics = new ArrayList<>();
}

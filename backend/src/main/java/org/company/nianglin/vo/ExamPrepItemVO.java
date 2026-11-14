package org.company.nianglin.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;
import org.company.nianglin.entity.SysDict;

/**
 * 检查准备须知条目（V5 · B，docs/api/14-exam-prep.md §1）。
 *
 * <p>四个字段与 {@code sys_dict} 的 EXAM_PREP 行一一对应：
 * {@code code} ← dict_code、{@code name} ← dict_label、
 * {@code requirement} ← dict_value、{@code remark} ← remark
 * （remark 必须原样带出「来源 URL + 免责声明」，这是本模块的合规载体）。</p>
 *
 * @author 银龄伴诊团队
 * @since V5
 */
@Data
@Accessors(chain = true)
@Schema(description = "检查准备须知条目")
public class ExamPrepItemVO {

    @Schema(description = "字典编码（类型内唯一）", example = "FASTING_BLOOD")
    private String code;

    @Schema(description = "检查项目名", example = "空腹血糖 / 静脉采血")
    private String name;

    @Schema(description = "一句话核心要求", example = "空腹 8–12 小时")
    private String requirement;

    @Schema(description = "完整转述 + 来源 URL + 免责声明")
    private String remark;

    /** 字典行 → VO；列名与字段语义的唯一映射点 */
    public static ExamPrepItemVO of(SysDict row) {
        return new ExamPrepItemVO()
                .setCode(row.getDictCode())
                .setName(row.getDictLabel())
                .setRequirement(row.getDictValue())
                .setRemark(row.getRemark());
    }
}

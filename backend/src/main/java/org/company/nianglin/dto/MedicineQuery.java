package org.company.nianglin.dto;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.company.nianglin.common.PageQuery;
import org.company.nianglin.entity.MedicineDict;

/**
 * 药品字典查询入参（{@code docs/api/05-medication.md} §1）。
 *
 * <p>药品字典是全站唯一「所有人都能读」的药品数据源，因此查询条件里
 * 没有任何归属相关字段 —— 没有可越权的东西。</p>
 *
 * @author 银龄伴诊团队
 * @since M6
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "药品字典查询入参")
public class MedicineQuery extends PageQuery<MedicineDict> {

    @Schema(description = "按通用名 / 商品名模糊搜索", example = "氨氯地平")
    @Size(max = 50, message = "搜索关键字不能超过 50 个字符")
    private String keyword;

    @Schema(description = "按剂型筛选：TABLET / CAPSULE / INJECTION / LIQUID / OTHER", example = "TABLET")
    private String dosageForm;

    /** MyBatis-Plus 分页对象 */
    public Page<MedicineDict> toMpPage() {
        return toPage(new Page<>());
    }
}

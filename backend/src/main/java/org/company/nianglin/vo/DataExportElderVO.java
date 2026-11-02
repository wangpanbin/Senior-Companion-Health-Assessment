package org.company.nianglin.vo;

import com.alibaba.excel.annotation.ExcelProperty;
import com.alibaba.excel.annotation.write.style.ColumnWidth;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 个人数据导出 · 「绑定老人」sheet 行（V4 · P0-1）。
 *
 * <p>仅 FAMILY 有数据（COMPANION 导出时本 sheet 只有表头）。
 * 老人姓名过 {@code MaskUtil.name}；身份证号 / 联系电话不属于导出范围。</p>
 *
 * @author 银龄伴诊团队
 * @since V4
 */
@Data
@Schema(description = "个人数据导出 · 绑定老人行")
public class DataExportElderVO {

    @ExcelProperty(value = "老人档案 ID", index = 0)
    @ColumnWidth(14)
    private Long elderId;

    @ExcelProperty(value = "姓名", index = 1)
    @ColumnWidth(14)
    private String elderName;

    @ExcelProperty(value = "与老人关系", index = 2)
    @ColumnWidth(14)
    private String relationLabel;

    @ExcelProperty(value = "绑定方式", index = 3)
    @ColumnWidth(14)
    private String bindTypeLabel;

    @ExcelProperty(value = "关系状态", index = 4)
    @ColumnWidth(12)
    private String statusLabel;

    @ExcelProperty(value = "绑定时间", index = 5)
    @ColumnWidth(20)
    private String bindTime;
}

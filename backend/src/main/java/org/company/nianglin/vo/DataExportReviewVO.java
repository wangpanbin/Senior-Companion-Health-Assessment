package org.company.nianglin.vo;

import com.alibaba.excel.annotation.ExcelProperty;
import com.alibaba.excel.annotation.write.style.ColumnWidth;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 个人数据导出 · 「评价记录」sheet 行（V4 · P0-1）。
 *
 * <p>家属导出「我发出的评价」，陪诊员导出「评价我的评价」，
 * 由 {@code 我的角色} 列区分。</p>
 *
 * @author 银龄伴诊团队
 * @since V4
 */
@Data
@Schema(description = "个人数据导出 · 评价记录行")
public class DataExportReviewVO {

    @ExcelProperty(value = "订单号", index = 0)
    @ColumnWidth(22)
    private String orderNo;

    @ExcelProperty(value = "我的角色", index = 1)
    @ColumnWidth(14)
    private String myRoleLabel;

    @ExcelProperty(value = "评分", index = 2)
    @ColumnWidth(10)
    private Integer score;

    @ExcelProperty(value = "评价内容", index = 3)
    @ColumnWidth(50)
    private String content;

    @ExcelProperty(value = "是否匿名", index = 4)
    @ColumnWidth(12)
    private String anonymousLabel;

    @ExcelProperty(value = "评价时间", index = 5)
    @ColumnWidth(20)
    private String createTime;
}

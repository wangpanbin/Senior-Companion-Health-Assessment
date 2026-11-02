package org.company.nianglin.vo;

import com.alibaba.excel.annotation.ExcelProperty;
import com.alibaba.excel.annotation.write.style.ColumnWidth;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 个人数据导出 · 「订单记录」sheet 行（V4 · P0-1）。
 *
 * <p>本人作为下单家属或接单陪诊员的全部订单。刻意<b>不含</b>对方姓名、
 * 详细地址、备注、坐标 —— 数据可携带不等于把别人的信息也一起打包。</p>
 *
 * @author 银龄伴诊团队
 * @since V4
 */
@Data
@Schema(description = "个人数据导出 · 订单记录行")
public class DataExportOrderVO {

    @ExcelProperty(value = "订单号", index = 0)
    @ColumnWidth(22)
    private String orderNo;

    @ExcelProperty(value = "我的角色", index = 1)
    @ColumnWidth(14)
    private String myRoleLabel;

    @ExcelProperty(value = "医院", index = 2)
    @ColumnWidth(24)
    private String hospital;

    @ExcelProperty(value = "科室", index = 3)
    @ColumnWidth(16)
    private String department;

    @ExcelProperty(value = "就诊时间", index = 4)
    @ColumnWidth(20)
    private String visitTime;

    @ExcelProperty(value = "订单状态", index = 5)
    @ColumnWidth(12)
    private String statusLabel;

    @ExcelProperty(value = "服务费(元)", index = 6)
    @ColumnWidth(12)
    private String fee;

    @ExcelProperty(value = "结算状态", index = 7)
    @ColumnWidth(12)
    private String paymentStatusLabel;

    @ExcelProperty(value = "下单时间", index = 8)
    @ColumnWidth(20)
    private String createTime;
}

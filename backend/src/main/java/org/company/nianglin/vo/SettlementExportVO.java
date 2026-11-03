package org.company.nianglin.vo;

import com.alibaba.excel.annotation.ExcelProperty;
import com.alibaba.excel.annotation.write.style.ColumnWidth;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 结算单导出行（{@code docs/api/09-statistics-export.md} §9）。
 *
 * <h3>时间与金额都由 Service 先格式化</h3>
 *
 * <p>EasyExcel 对 {@code LocalDateTime} 的默认写出格式与文档约定的
 * {@code yyyy-MM-dd HH:mm:ss} 不一致；金额则要求固定两位小数。
 * 导出文件是交付物，格式不该由类库默认值决定，因此这里全部用字符串。</p>
 *
 * <h3>12 列，逐列显式 index</h3>
 *
 * <p>每列都写 {@code index}，避免以后调整字段顺序时整表数据错列。</p>
 *
 * <h3>合规：不含手机号、身份证号</h3>
 *
 * <p>三个姓名列由 Service 脱敏后传入；文件一旦落到个人电脑上就脱离了平台访问控制，
 * 因此这里的脱敏比页面更严格。</p>
 *
 * @author 银龄伴诊团队
 * @since V4 · P2-1
 */
@Data
@Accessors(chain = true)
@Schema(description = "结算单导出行")
public class SettlementExportVO {

    @ExcelProperty(value = "订单号", index = 0)
    @ColumnWidth(22)
    private String orderNo;

    @ExcelProperty(value = "家属姓名", index = 1)
    @ColumnWidth(12)
    private String familyName;

    @ExcelProperty(value = "老人姓名", index = 2)
    @ColumnWidth(12)
    private String elderName;

    @ExcelProperty(value = "陪诊员", index = 3)
    @ColumnWidth(12)
    private String companionName;

    @ExcelProperty(value = "医院", index = 4)
    @ColumnWidth(24)
    private String hospital;

    @ExcelProperty(value = "科室", index = 5)
    @ColumnWidth(16)
    private String department;

    @ExcelProperty(value = "就诊时间", index = 6)
    @ColumnWidth(20)
    private String visitTime;

    @ExcelProperty(value = "服务完成时间", index = 7)
    @ColumnWidth(20)
    private String finishTime;

    @ExcelProperty(value = "服务费(元)", index = 8)
    @ColumnWidth(12)
    private String fee;

    @ExcelProperty(value = "结算金额(元)", index = 9)
    @ColumnWidth(14)
    private String settleAmount;

    @ExcelProperty(value = "结算状态", index = 10)
    @ColumnWidth(12)
    private String paymentStatusLabel;

    @ExcelProperty(value = "订单状态", index = 11)
    @ColumnWidth(12)
    private String statusLabel;
}

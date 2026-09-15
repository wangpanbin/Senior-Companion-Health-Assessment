package org.company.nianglin.vo;

import com.alibaba.excel.annotation.ExcelProperty;
import com.alibaba.excel.annotation.write.style.ColumnWidth;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 订单导出行（{@code docs/api/09-statistics-export.md} §6）。
 *
 * <h3>用字符串字段而不是 {@code LocalDateTime}</h3>
 *
 * <p>EasyExcel 对 {@code LocalDateTime} 的默认写出是 {@code 2026-09-15T16:40}，
 * 带一个 {@code T}，而且没有秒。验收项要求 {@code yyyy-MM-dd HH:mm:ss}，
 * 因此这里让 Service 先格式化好再放进来 —— 导出文件的格式是<b>交付物的一部分</b>
 * （用户会直接拿去打印或转发），不该由类库的默认行为决定。</p>
 *
 * <h3>列的顺序就是字段顺序</h3>
 *
 * <p>每列都显式写 {@code index}，避免以后有人调整字段顺序时列错位 ——
 * {@code @ExcelProperty} 只写列名时是按声明顺序排列的，
 * 而「顺序变了但没人注意到」在导出场景里意味着整表数据错列。</p>
 *
 * <h3>合规：本表不含密码、身份证号、完整手机号</h3>
 *
 * <p>老人姓名与陪诊员姓名由 Service 脱敏后传入；
 * 家属姓名同样脱敏。导出文件一旦落到个人电脑上就脱离了平台的访问控制，
 * 因此这里的脱敏比页面更严格。</p>
 *
 * @author 银龄伴诊团队
 * @since M10
 */
@Data
@Schema(description = "订单导出行")
public class OrderExportVO {

    @ExcelProperty(value = "订单号", index = 0)
    @ColumnWidth(22)
    private String orderNo;

    @ExcelProperty(value = "老人姓名", index = 1)
    @ColumnWidth(12)
    private String elderName;

    @ExcelProperty(value = "老人年龄", index = 2)
    @ColumnWidth(10)
    private Integer elderAge;

    @ExcelProperty(value = "家属姓名", index = 3)
    @ColumnWidth(12)
    private String familyName;

    @ExcelProperty(value = "陪诊员", index = 4)
    @ColumnWidth(12)
    private String companionName;

    @ExcelProperty(value = "医院", index = 5)
    @ColumnWidth(24)
    private String hospital;

    @ExcelProperty(value = "科室", index = 6)
    @ColumnWidth(16)
    private String department;

    @ExcelProperty(value = "就诊时间", index = 7)
    @ColumnWidth(20)
    private String visitTime;

    @ExcelProperty(value = "订单状态", index = 8)
    @ColumnWidth(12)
    private String statusLabel;

    @ExcelProperty(value = "服务费", index = 9)
    @ColumnWidth(12)
    private String fee;

    @ExcelProperty(value = "结算状态", index = 10)
    @ColumnWidth(12)
    private String paymentStatusLabel;

    @ExcelProperty(value = "下单时间", index = 11)
    @ColumnWidth(20)
    private String createTime;

    @ExcelProperty(value = "接单时间", index = 12)
    @ColumnWidth(20)
    private String acceptTime;

    @ExcelProperty(value = "完成时间", index = 13)
    @ColumnWidth(20)
    private String finishTime;
}

package org.company.nianglin.vo;

import com.alibaba.excel.annotation.ExcelProperty;
import com.alibaba.excel.annotation.write.style.ColumnWidth;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 用户导出行（{@code docs/api/09-statistics-export.md} §7）。
 *
 * <h3>绝不包含密码、身份证号、完整手机号（合规红线 + 验收项）</h3>
 *
 * <p>这不是「注意一下」的级别：导出的 Excel 会被保存、转发、打印，
 * 一旦带着完整手机号流出去，平台就说不清它是怎么泄露的。
 * 因此本类里<b>只保留脱敏后的 {@code phone}</b>，字段名也直接叫 {@code phone}
 * 但语义是脱敏串 —— 这一点由 Service 保证，且有一条端到端断言专门检查
 * 导出结果中不出现连续 11 位数字。</p>
 *
 * <p>{@code password} 字段<b>根本不定义</b>：不定义就永远不可能被导出，
 * 比「定义了但记得不赋值」可靠得多。</p>
 *
 * @author 银龄伴诊团队
 * @since M10
 */
@Data
@Schema(description = "用户导出行")
public class UserExportVO {

    @ExcelProperty(value = "用户 ID", index = 0)
    @ColumnWidth(12)
    private Long id;

    @ExcelProperty(value = "用户名", index = 1)
    @ColumnWidth(18)
    private String username;

    @ExcelProperty(value = "昵称", index = 2)
    @ColumnWidth(16)
    private String nickname;

    @ExcelProperty(value = "手机号", index = 3)
    @ColumnWidth(16)
    private String phone;

    @ExcelProperty(value = "角色", index = 4)
    @ColumnWidth(12)
    private String roleLabel;

    @ExcelProperty(value = "账号状态", index = 5)
    @ColumnWidth(12)
    private String statusLabel;

    @ExcelProperty(value = "关联订单数", index = 6)
    @ColumnWidth(12)
    private Integer orderCount;

    @ExcelProperty(value = "注册时间", index = 7)
    @ColumnWidth(20)
    private String createTime;

    @ExcelProperty(value = "最后登录时间", index = 8)
    @ColumnWidth(20)
    private String lastLoginTime;
}

package org.company.nianglin.vo;

import com.alibaba.excel.annotation.ExcelProperty;
import com.alibaba.excel.annotation.write.style.ColumnWidth;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 个人数据导出 · 「个人资料」sheet 行（V4 · P0-1）。
 *
 * <p>对应 {@code docs/api/01-auth-user.md} §9。与 {@link UserExportVO} 同一纪律：
 * <b>password 字段根本不定义</b>，手机号 / 姓名在装配时已过 {@code MaskUtil} ——
 * 导出文件会被保存、转发，不能带着完整敏感字段流出去。</p>
 *
 * @author 银龄伴诊团队
 * @since V4
 */
@Data
@Schema(description = "个人数据导出 · 个人资料行")
public class DataExportProfileVO {

    @ExcelProperty(value = "用户 ID", index = 0)
    @ColumnWidth(12)
    private Long id;

    @ExcelProperty(value = "用户名", index = 1)
    @ColumnWidth(18)
    private String username;

    @ExcelProperty(value = "昵称", index = 2)
    @ColumnWidth(16)
    private String nickname;

    @ExcelProperty(value = "真实姓名", index = 3)
    @ColumnWidth(14)
    private String realName;

    @ExcelProperty(value = "手机号", index = 4)
    @ColumnWidth(16)
    private String phone;

    @ExcelProperty(value = "角色", index = 5)
    @ColumnWidth(12)
    private String roleLabel;

    @ExcelProperty(value = "账号状态", index = 6)
    @ColumnWidth(12)
    private String statusLabel;

    @ExcelProperty(value = "注册时间", index = 7)
    @ColumnWidth(20)
    private String createTime;
}

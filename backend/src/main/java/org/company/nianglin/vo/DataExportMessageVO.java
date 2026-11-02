package org.company.nianglin.vo;

import com.alibaba.excel.annotation.ExcelProperty;
import com.alibaba.excel.annotation.write.style.ColumnWidth;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 个人数据导出 · 「消息记录」sheet 行（V4 · P0-1）。
 *
 * <p>本人的站内信收件箱。正文在发信时已由后端模板填充并脱敏
 * （见 {@code InternalMessage#content} 的字段注释），导出时原样写出。</p>
 *
 * @author 银龄伴诊团队
 * @since V4
 */
@Data
@Schema(description = "个人数据导出 · 消息记录行")
public class DataExportMessageVO {

    @ExcelProperty(value = "标题", index = 0)
    @ColumnWidth(30)
    private String title;

    @ExcelProperty(value = "消息类型", index = 1)
    @ColumnWidth(16)
    private String typeLabel;

    @ExcelProperty(value = "正文", index = 2)
    @ColumnWidth(60)
    private String content;

    @ExcelProperty(value = "是否已读", index = 3)
    @ColumnWidth(12)
    private String readLabel;

    @ExcelProperty(value = "接收时间", index = 4)
    @ColumnWidth(20)
    private String createTime;
}

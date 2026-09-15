package org.company.nianglin.service.impl;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.write.style.column.LongestMatchColumnWidthStyleStrategy;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.company.nianglin.exception.BusinessException;
import org.company.nianglin.common.ResultCode;
import org.company.nianglin.service.ExportService;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Excel 导出实现。
 *
 * @author 银龄伴诊团队
 * @since M10
 */
@Slf4j
@Service
public class ExportServiceImpl implements ExportService {

    /** 文件名里的时间戳：{@code 订单数据_20260915_104500.xlsx} */
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private static final String XLSX_CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    @Override
    public <T> void writeExcel(HttpServletResponse response, String baseFileName,
                               Class<T> rowClass, List<T> rows, String sheetName) {
        String fileName = baseFileName + "_" + LocalDateTime.now().format(FILE_TIME) + ".xlsx";
        String encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");

        response.reset();
        response.setContentType(XLSX_CONTENT_TYPE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        // 同时给 filename（ASCII 回退）与 filename*（RFC 5987）：只给后者时，
        // 少数老浏览器会退化成「下载」这种没有意义的名字
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"export.xlsx\"; filename*=UTF-8''" + encoded);

        try (OutputStream out = response.getOutputStream()) {
            EasyExcel.write(out, rowClass)
                    .sheet(sheetName)
                    // 自适应列宽：数字与中文混排时，固定列宽要么截断要么留大片空白
                    .registerWriteHandler(new LongestMatchColumnWidthStyleStrategy())
                    .doWrite(rows);
            log.info("Excel 导出完成 | 文件={} | 行数={}", fileName, rows == null ? 0 : rows.size());
        } catch (IOException e) {
            // 走到这里通常不是「写文件失败」而是「客户端中途断开下载」，
            // 此时响应头已经发出去了，回不了 JSON 错误体，只能记日志
            log.warn("Excel 导出写出失败（可能是客户端中断下载） | 行数={} | {}",
                    rows == null ? 0 : rows.size(), e.getMessage());
        } catch (RuntimeException e) {
            log.error("Excel 导出失败 | 行数={}", rows == null ? 0 : rows.size(), e);
            throw new BusinessException(ResultCode.SYSTEM_ERROR, "导出失败，请稍后重试");
        }
    }
}

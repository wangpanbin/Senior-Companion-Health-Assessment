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
        // 响应已经提交时立刻收手：此时 response.reset() 会抛 IllegalStateException
        // （「Cannot reset buffer - response is already committed」），
        // 而那是个**不受检的**异常，会整个逃出本方法、落到 GlobalExceptionHandler ——
        // 响应体早已发出去，兜底处理器再写一次统一错误体必然失败，
        // 于是「重复导出 / 已被拦截器写过响应」变成一串没有意义的 ERROR 堆栈（M8 同款噪声）。
        if (response.isCommitted()) {
            log.warn("响应已提交，本次导出放弃 | 文件={} | 行数={}", baseFileName, rows == null ? 0 : rows.size());
            return;
        }

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
            // ⚠️ 实测（M12）：上面的 catch(IOException) 实际上接不到「客户端断开」。
            // EasyExcel 在 finish() 阶段会用自己包装的异常替换掉原始异常
            // （消息固定为 Can not close IO，cause 是关闭输出流时的 IOException），
            // 原始异常类型在那一刻已经丢了 —— 因此**不能靠异常类型或 cause 链来区分**
            // 「客户端断开」与「真的生成失败」，只能看响应本身。
            //
            // 判据沿用 GlobalExceptionHandler 对 IOException 的同一套逻辑：
            // 响应一旦提交，就不可能再写回结构化错误，此时记 WARN 并放弃写回；
            // 反之（一个字节都没发出去）说明是真正的生成故障，必须抛出去让前端能提示。
            if (response.isCommitted()) {
                log.warn("Excel 导出中断（响应已提交，客户端多半已取消下载） | 行数={} | {}",
                        rows == null ? 0 : rows.size(), e.getMessage());
                return;
            }
            log.error("Excel 导出失败 | 行数={}", rows == null ? 0 : rows.size(), e);
            throw new BusinessException(ResultCode.SYSTEM_ERROR, "导出失败，请稍后重试");
        }
    }
}

package org.company.nianglin.service;

import jakarta.servlet.http.HttpServletResponse;

import java.util.List;

/**
 * Excel 导出（EasyExcel 流式写出）。
 *
 * <p>对应 {@code docs/api/09-statistics-export.md} §6 / §7。</p>
 *
 * <h3>为什么单独抽一个服务</h3>
 *
 * <p>「把一堆 VO 写成 xlsx 并塞进 HTTP 响应」这件事与统计口径毫无关系，
 * 却有几个必须一次做对的技术细节：中文文件名要 URL 编码（否则部分浏览器乱码）、
 * 响应头必须是 {@code attachment}、写出过程不能把整个列表复制一遍。
 * 把它留在统计服务里，将来第二个模块要导出时只能复制粘贴，
 * 而复制粘贴的代码里，文件名编码几乎一定会漏。</p>
 *
 * <h3>为什么不用统一响应结构包一层</h3>
 *
 * <p>文档明确写了「Body 为二进制流（不走统一响应结构，前端拦截器已做透传处理）」。
 * 导出接口一旦返回 {@code Result}，浏览器下载到的就是一个
 * 装着 base64 或乱码的 JSON 文件。</p>
 *
 * @author 银龄伴诊团队
 * @since M10
 */
public interface ExportService {

    /**
     * 把数据写成 xlsx 并写入响应。
     *
     * <p>本方法<b>不抛业务异常</b>：响应头一旦写出就无法回退成 JSON 错误体，
     * 因此调用方必须先把数据准备好（包括行数校验），再调用本方法。</p>
     *
     * @param response      HTTP 响应
     * @param baseFileName  文件名前缀（不含扩展名），如「订单数据」
     * @param rowClass      EasyExcel 行模型（用 {@code @ExcelProperty} 定义列）
     * @param rows          数据行，允许为空（导出只有表头的空文件，而不是报错）
     * @param sheetName     工作表名
     */
    <T> void writeExcel(HttpServletResponse response, String baseFileName,
                        Class<T> rowClass, List<T> rows, String sheetName);
}

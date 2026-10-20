package org.company.nianglin.constant;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.regex.Pattern;

/**
 * 上传产物的落盘布局（V3.2 · 单一真源）。
 *
 * <h3>为什么要有这一层</h3>
 *
 * <p>上传一律写进 {@code ./uploads/{yyyyMM}/{uuid}.{ext}}，对外 URL 是
 * {@code /uploads/{yyyyMM}/{uuid}.{ext}}（AGENTS.md §4.5）。</p>
 *
 * <p>这个布局有<b>两个</b>消费方：</p>
 * <ul>
 *   <li>{@code FileStorageServiceImpl} —— 决定文件落在哪个目录、对外 URL 怎么拼；</li>
 *   <li>{@code UploadAccessGuardFilter} —— 决定哪些路径属于「上传产物」从而进入失败关闭判定。</li>
 * </ul>
 *
 * <p>两边各写一份形状，布局一改，<b>守卫会静默失效</b>：
 * 守卫不再拦那些路径，证件的原始 URL 就重新变得可公开直连 ——
 * 而这件事没有任何报错。所以布局只能写在这一处，两边都引用它。</p>
 *
 * @author 银龄伴诊团队
 * @since V3.2
 */
public final class UploadPathLayout {

    /** 上传目录的 URL 前缀 */
    public static final String URL_PREFIX = "/uploads/";

    /** 落盘按月分段：{@code {yyyyMM}} */
    private static final DateTimeFormatter MONTH_DIR_FORMAT = DateTimeFormatter.ofPattern("yyyyMM");

    /** 落盘目录的形状：{@code {yyyyMM}/} */
    private static final Pattern MONTH_DIR_PATTERN = Pattern.compile("^\\d{6}/");

    private UploadPathLayout() {
        throw new UnsupportedOperationException("常量类不允许实例化");
    }

    /**
     * 取某个时刻对应的落盘月份目录名。
     *
     * @param time 时间点
     * @return 六位月份目录名，如 {@code 202610}
     */
    public static String monthDir(LocalDateTime time) {
        return time.format(MONTH_DIR_FORMAT);
    }

    /**
     * 拼对外 URL。
     *
     * @param monthDir   月份目录名
     * @param storedName 存储文件名
     * @return 形如 {@code /uploads/202610/xxx.png} 的 URL
     */
    public static String url(String monthDir, String storedName) {
        return URL_PREFIX + monthDir + "/" + storedName;
    }

    /**
     * 判断一个请求路径是否落在上传产物的落盘布局里。
     *
     * <p>只有落在这个形状里的路径才可能是上传产物（因而可能是证件），
     * 需要进入失败关闭判定；其它同目录下的静态文件（例如种子给全体用户填的
     * 占位头像 {@code /uploads/avatar/default.png}）<b>不可能是证件</b>。</p>
     *
     * @param uri 请求路径
     * @return 是上传产物路径返回 {@code true}
     */
    public static boolean isUploadPath(String uri) {
        if (uri == null || !uri.startsWith(URL_PREFIX)) {
            return false;
        }
        return MONTH_DIR_PATTERN.matcher(uri.substring(URL_PREFIX.length())).find();
    }
}

package org.company.nianglin.security;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.company.nianglin.common.Result;
import org.company.nianglin.common.ResultCode;
import org.company.nianglin.constant.FileBizType;
import org.company.nianglin.entity.SysFile;
import org.company.nianglin.mapper.SysFileMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 上传目录访问守卫：拦截对资质证件（{@code bizType=COMPANION_CERT}）原始 URL 的直连。
 *
 * <h3>为什么要单独拦证件</h3>
 *
 * <p>{@code /uploads/**} 在安全链上整体免登录，这是刻意保留的：打卡照片、头像、
 * 聊天媒体都以 {@code <img>} 直连渲染，一旦加鉴权头这些页面立即回归（{@code <img>}
 * 无法携带 {@code Authorization}）。但资质证件含身份证等强 PII，URL 一旦泄露
 * （分享 / 日志 / Referer）就会被<b>未认证者长期访问</b>，因此需要区别对待。</p>
 *
 * <p>由于落盘路径只按月份分段（{@code /uploads/{yyyyMM}/{uuid}.{ext}}），证件与
 * 照片<b>混放在同一目录</b>，无法按路径前缀区分。所以判据取自登记表：
 * 用文件名反查 {@code sys_file}，命中 {@code bizType=COMPANION_CERT} 即拒绝直连。</p>
 *
 * <p><b>失败关闭（deny-by-default）</b>：登记表里<b>查不到</b>的文件（未登记、
 * 或记录被逻辑删除）同样拒绝 —— 否则只要删掉 {@code sys_file} 记录，
 * 证件的原始 URL 就会重新变得可公开直连，收紧形同虚设。因此放行的条件是
 * 「<b>命中登记表且不是证件</b>」，而不是「不是证件」。</p>
 *
 * <p><b>守卫范围 = 落盘布局</b>：只有 {@code /uploads/{yyyyMM}/} 下的路径才进入
 * 失败关闭判定（上传一律写进这个形状，见 {@code FileStorageServiceImpl}）。
 * 不在这个布局里的路径（如种子给全体用户填的占位头像
 * {@code /uploads/avatar/default.png}，该文件从来就不存在）<b>不可能是证件</b>，
 * 放行不损失任何安全性 —— 收窄之后，证件保护一点没弱化。</p>
 *
 * <p>证件改由带鉴权的端点 {@code GET /api/file/cert?url=...} 供给（上传人本人或管理员），
 * 前端证件预览改走该端点取 blob URL。已登记的其余业务类型（头像 / 打卡照片 /
 * 聊天媒体）不受影响，仍按原路径公开访问。</p>
 *
 * <p>同时承担上传目录响应的<b>加固头</b>：为所有 {@code /uploads/**} 响应补
 * {@code X-Content-Type-Options: nosniff} 与 {@code Content-Disposition: inline}，
 * 防止浏览器 MIME 嗅探把伪装成图片的文件当脚本/文档执行。</p>
 *
 * @author 银龄伴诊团队
 * @since 安全加固 2026-10
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UploadAccessGuardFilter extends OncePerRequestFilter {

    private static final String UPLOAD_PREFIX = "/uploads/";

    /**
     * 上传产物的落盘目录形状：{@code /uploads/{yyyyMM}/{uuid}.{ext}}。
     *
     * <p>见 {@code FileStorageServiceImpl}：所有上传（含全部证件）都写进
     * {@code ./uploads/{yyyyMM}/}，{@code sys_file.url} 也是 {@code /uploads/{yyyyMM}/{storedName}}。
     * 所以<b>只有这个形状下的路径才可能是上传产物</b>。</p>
     */
    private static final Pattern MONTH_DIR_PATTERN = Pattern.compile("^\\d{6}/");

    /** 仅静态资源读取方法需要守卫 */
    private static final Set<String> GUARDED_METHODS = Set.of("GET", "HEAD");

    private final SysFileMapper sysFileMapper;
    private final ObjectMapper objectMapper;

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        String uri = request.getRequestURI();
        // 只有上传目录需要处理；其余路径直接放行，避免无谓的登记表查询
        if (uri == null || !uri.startsWith(UPLOAD_PREFIX)) {
            filterChain.doFilter(request, response);
            return;
        }

        // 响应加固：所有上传目录静态响应都禁止 MIME 嗅探（扩展名已在落盘时按文件头判定，
        // 与真实类型一致），并以 inline 呈现 —— 打卡照片 / 头像 / 聊天媒体都是页面内联图片
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "inline");

        if (!GUARDED_METHODS.contains(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }

        // ⚠️ 守卫只管「上传产物」，不管同目录下的其它静态文件。
        //
        // 判据是落盘布局：上传一律写进 /uploads/{yyyyMM}/{uuid}.{ext}（见
        // FileStorageServiceImpl），所以证件必然在这个形状里 —— 收窄到它，
        // 证件保护一点没弱化。
        //
        // 收窄之前，凡是 /uploads/** 下查不到登记的文件一律 403（失败关闭），
        // 于是种子给所有用户填的占位头像 /uploads/avatar/default.png
        // （这个文件从来就不存在）也被当成证件挡掉 —— 老人端与「我的」页
        // 因为要渲染 <img src=头像> 而稳定产生一条 403 console error，
        // 把 6 条既有 E2E 打成红的。
        //
        // 失败关闭的前提是「这个路径可能是上传产物」；不在落盘布局里的路径
        // （如 /uploads/avatar/default.png）不可能是证件，放行不损失任何安全性。
        String rest = uri.substring(UPLOAD_PREFIX.length());
        if (!MONTH_DIR_PATTERN.matcher(rest).find()) {
            filterChain.doFilter(request, response);
            return;
        }

        String storedName = extractStoredName(uri);
        if (storedName == null) {
            filterChain.doFilter(request, response);
            return;
        }

        SysFile file = sysFileMapper.selectOne(new LambdaQueryWrapper<SysFile>()
                .eq(SysFile::getStoredName, storedName)
                .last("limit 1"));

        // 失败关闭：命中登记表且不是证件（头像 / 打卡照片 / 聊天媒体）才放行；
        // 查不到登记（未登记 / 记录被逻辑删除）一律按证件同样拒绝 —— 否则只要把
        // sys_file 记录删掉，证件原始 URL 就会重新变成公开可直连，收紧形同虚设。
        if (file != null && !FileBizType.COMPANION_CERT.name().equals(file.getBizType())) {
            filterChain.doFilter(request, response);
            return;
        }

        log.warn("上传文件直连被拒（证件或未登记） | {}", uri);
        SecurityJsonWriter.write(response, objectMapper, HttpStatus.FORBIDDEN.value(),
                Result.fail(ResultCode.FORBIDDEN, "该文件不可直接访问，需通过鉴权接口获取"));
    }

    /**
     * 从 {@code /uploads/{yyyyMM}/{storedName}} 中取出文件名。
     *
     * @return 文件名；路径不含 {@code /uploads/} 或缺少文件名时返回 {@code null}
     */
    private static String extractStoredName(String uri) {
        if (uri == null) {
            return null;
        }
        int idx = uri.indexOf(UPLOAD_PREFIX);
        if (idx < 0) {
            return null;
        }
        String rest = uri.substring(idx + UPLOAD_PREFIX.length());
        int slash = rest.lastIndexOf('/');
        if (slash < 0 || slash == rest.length() - 1) {
            return null;
        }
        return rest.substring(slash + 1);
    }
}

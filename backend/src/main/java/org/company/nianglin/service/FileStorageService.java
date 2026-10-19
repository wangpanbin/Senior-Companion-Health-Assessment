package org.company.nianglin.service;

import org.company.nianglin.vo.FileUploadVO;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;

/**
 * 附件统一存储服务。
 *
 * <p>{@code sys_file} 是 M1 就建好的统一附件登记表，业务侧（打卡照片、
 * 投诉证据、资质证件、头像）都只是它的不同 {@code bizType}。
 * 把「校验文件头 → UUID 重命名 → 落盘 → 登记表」这一串抽出来，
 * 是为了避免三个模块各写一遍 —— 三份实现里一定有一份忘了校验文件头。</p>
 *
 * <p>对外只暴露「存」这一个动作：{@code fileId} 是登记表自己的事，
 * 调用方要的只是「存好了，URL 在这」。</p>
 *
 * @author 银龄伴诊团队
 * @since M5
 */
public interface FileStorageService {

    /**
     * 存一个上传文件。
     *
     * @param file       上传的文件（不得为空）
     * @param bizType    业务类型：{@code CHECKIN} / {@code COMPLAINT} / {@code COMPANION_CERT} / {@code AVATAR} / {@code REVIEW}
     * @param bizId      业务主键 ID，可为 {@code null}
     * @param uploaderId 上传人用户 ID
     * @return 文件标识与可访问 URL
     */
    FileUploadVO store(MultipartFile file, String bizType, Long bizId, Long uploaderId);

    /**
     * 受控读取资质证件（{@code bizType=COMPANION_CERT}）。
     *
     * <p>证件类属于刻意收紧的一类：{@code /uploads/**} 整体仍免登录（打卡照片、头像、
     * 聊天媒体继续支持 {@code <img>} 直连），但证件文件的原始 URL 一律被
     * {@link org.company.nianglin.security.UploadAccessGuardFilter} 拦截，
     * 只允许通过带鉴权的下载端点读取，避免 URL 泄露后被未认证者长期访问。</p>
     *
     * <p>授权口径：<b>上传人本人或管理员</b>（管理员需在资质审核中查看证件）。
     * 非证件类型、文件不存在、非授权人一律拒绝。</p>
     *
     * @param url         证件文件的可访问 URL（形如 {@code /uploads/202609/xxx.jpg}）
     * @param requesterId 当前请求人用户 ID
     * @param isAdmin     当前请求人是否为管理员
     * @return 磁盘路径与响应元数据
     */
    CertContent loadCert(String url, Long requesterId, boolean isAdmin);

    /**
     * 资质证件的受控读取结果。
     *
     * @param path        磁盘绝对/相对路径
     * @param contentType 响应内容类型（按落库的真实 MIME）
     * @param originName  原始文件名（仅用于下载响应头，避免以 UUID 名示人）
     */
    record CertContent(Path path, String contentType, String originName) {
    }
}

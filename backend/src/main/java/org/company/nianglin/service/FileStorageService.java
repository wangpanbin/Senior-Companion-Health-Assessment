package org.company.nianglin.service;

import org.company.nianglin.vo.FileUploadVO;
import org.springframework.web.multipart.MultipartFile;

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
}

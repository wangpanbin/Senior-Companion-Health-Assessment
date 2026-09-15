package org.company.nianglin.service;

import org.company.nianglin.vo.CaptchaVO;

/**
 * 图形验证码服务。
 *
 * @author 银龄伴诊团队
 */
public interface CaptchaService {

    /**
     * 生成一张新验证码。
     *
     * @return 验证码标识 + base64 图片 + 有效期
     */
    CaptchaVO generate();

    /**
     * 校验验证码。
     *
     * <p>校验通过后该验证码<b>立即失效</b>，同一 key 无法二次使用（防重放）。</p>
     *
     * @param captchaKey  验证码标识
     * @param captchaCode 用户输入的验证码，大小写不敏感
     * @throws org.company.nianglin.exception.BusinessException 校验失败（{@code 1003}）
     */
    void validate(String captchaKey, String captchaCode);
}

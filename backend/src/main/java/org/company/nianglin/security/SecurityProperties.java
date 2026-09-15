package org.company.nianglin.security;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 安全策略配置。
 *
 * <p>绑定 {@code application.yml} 的 {@code nianglin.security.*}。
 * 注意身份证号加密密钥（{@code nianglin.security.id-card-key}）由
 * {@code AesUtil} 自行读取，不在此处重复绑定。</p>
 *
 * @author 银龄伴诊团队
 */
@Data
@Component
@ConfigurationProperties(prefix = "nianglin.security")
public class SecurityProperties {

    /**
     * 登录失败几次后锁定，默认 5。
     */
    private int loginFailThreshold = 5;

    /**
     * 锁定时长（分钟），默认 15。
     */
    private long loginLockMinutes = 15L;

    /**
     * 图形验证码有效期（秒），默认 300。
     */
    private long captchaExpireSeconds = 300L;

    /** 锁定时长（秒） */
    public long loginLockSeconds() {
        return loginLockMinutes * 60L;
    }
}

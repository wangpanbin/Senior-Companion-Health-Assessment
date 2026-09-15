package org.company.nianglin.security;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 安全策略配置。
 *
 * <p>绑定 {@code application.yml} 的 {@code nianglin.security.*}。</p>
 *
 * <p><b>关于 {@code id-card-key}</b>：M1~M2 阶段这个配置项写了但<b>没有任何代码读取它</b>
 * （原注释称「由 {@code AesUtil} 自行读取」是错的 —— {@code AesUtil} 是无状态静态工具，
 * 密钥必须由调用方传入）。M3 落档案与资质申请功能时把它接上，
 * 统一走 {@link #idCardKey()}，避免每个 Service 各自 {@code @Value} 注入一份。</p>
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

    /**
     * 身份证号等 PII 字段的 AES 加密密钥。
     *
     * <p>⚠️ 生产环境必须用环境变量 {@code ID_CARD_AES_KEY} 覆盖。
     * 一旦更换密钥，<b>已落库的密文将无法解密</b> —— 换密钥必须配套数据迁移脚本。</p>
     */
    private String idCardKey;

    /** 锁定时长（秒） */
    public long loginLockSeconds() {
        return loginLockMinutes * 60L;
    }

    /**
     * 取身份证加密密钥，未配置时直接抛异常。
     *
     * <p>刻意不返回 {@code null} 让 {@code AesUtil} 去报「密钥不能为空」——
     * 那会等到第一次写库才炸。这里提前炸，启动后第一次调用就能暴露配置问题。</p>
     */
    public String idCardKey() {
        if (idCardKey == null || idCardKey.isBlank()) {
            throw new IllegalStateException(
                    "未配置 nianglin.security.id-card-key，无法加解密身份证号");
        }
        return idCardKey;
    }
}

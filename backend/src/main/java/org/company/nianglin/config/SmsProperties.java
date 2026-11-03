package org.company.nianglin.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 短信通道配置（V4 · P1-1 自助找回密码）。
 *
 * <p>配置前缀 {@code nianglin.sms}。与环境无关的默认值放在 {@code application.yml}，
 * 凭证只允许来自环境变量（{@code application-prod.yml} 里的
 * {@code ${ALIYUN_SMS_ACCESS_KEY:}} 一类占位），<b>任何真实密钥都不得入库</b>。</p>
 *
 * <p><b>启动期校验</b>：{@code provider=aliyun} 时若任一凭证为空，直接抛
 * {@link IllegalStateException} 让进程启动失败 —— 不允许「配置漏了但静默降级成 mock」，
 * 否则线上会出现「用户点了发送、系统返回成功、短信永远不来」的哑故障。
 * {@code provider=mock} 不校验凭证（开发 / 测试 / e2e 用）。</p>
 *
 * @author 银龄伴诊团队
 * @since V4 · P1-1
 */
@Slf4j
@Data
@Component
@ConfigurationProperties(prefix = "nianglin.sms")
public class SmsProperties {

    public static final String PROVIDER_MOCK = "mock";
    public static final String PROVIDER_ALIYUN = "aliyun";

    /** 通道实现：{@code mock}（默认，仅日志）/ {@code aliyun}（Dysmsapi） */
    private String provider = PROVIDER_MOCK;

    /** 连接超时（毫秒），仅 aliyun 通道使用 */
    private int connectTimeoutMs = 3_000;

    /** 请求超时（毫秒），仅 aliyun 通道使用 */
    private int requestTimeoutMs = 5_000;

    /** 阿里云 AccessKeyId（环境变量 ALIYUN_SMS_ACCESS_KEY） */
    private String accessKeyId;

    /** 阿里云 AccessKeySecret（环境变量 ALIYUN_SMS_ACCESS_SECRET） */
    private String accessKeySecret;

    /** 阿里云短信签名（控制台审核通过的签名名称） */
    private String signName;

    /** 阿里云短信模板 Code（模板正文须含 ${code}） */
    private String templateCode;

    @PostConstruct
    public void validate() {
        String normalized = provider == null ? "" : provider.trim().toLowerCase();
        if (!PROVIDER_MOCK.equals(normalized) && !PROVIDER_ALIYUN.equals(normalized)) {
            throw new IllegalStateException(
                    "nianglin.sms.provider 仅支持 mock / aliyun，当前为：" + provider
                            + "。请检查 application-dev.yml 或环境变量 SMS_PROVIDER。");
        }
        if (connectTimeoutMs < 100 || requestTimeoutMs < 100) {
            throw new IllegalStateException(
                    "nianglin.sms 超时配置不合法（connect-timeout-ms / request-timeout-ms 必须 ≥ 100）。");
        }
        if (PROVIDER_ALIYUN.equals(normalized)) {
            requireText(accessKeyId, "nianglin.sms.access-key-id（环境变量 ALIYUN_SMS_ACCESS_KEY）");
            requireText(accessKeySecret, "nianglin.sms.access-key-secret（环境变量 ALIYUN_SMS_ACCESS_SECRET）");
            requireText(signName, "nianglin.sms.sign-name（环境变量 ALIYUN_SMS_SIGN_NAME）");
            requireText(templateCode, "nianglin.sms.template-code（环境变量 ALIYUN_SMS_TEMPLATE_CODE）");
        }
        log.info("短信通道配置已加载 | provider={} | connectTimeoutMs={} | requestTimeoutMs={}",
                normalized, connectTimeoutMs, requestTimeoutMs);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "nianglin.sms.provider=aliyun 时必须配置 " + name + "；未配置将拒绝启动，不静默降级为 mock。");
        }
    }
}

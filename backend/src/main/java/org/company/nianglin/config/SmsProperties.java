package org.company.nianglin.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.company.nianglin.constant.SmsScene;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.Map;

/**
 * 短信通道配置（V4 · P1-1 自助找回密码 / P1-3 站外提醒编排）。
 *
 * <p>配置前缀 {@code nianglin.sms}。与环境无关的默认值放在 {@code application.yml}，
 * 凭证只允许来自环境变量（{@code application-prod.yml} 里的
 * {@code ${ALIYUN_SMS_ACCESS_KEY:}} 一类占位），<b>任何真实密钥都不得入库</b>。</p>
 *
 * <p>模板按场景分开配置：目前只有 {@code templates.missed-dose}（漏服提醒）。
 * 原 {@code templates.verification}（找回密码验证码）已随去短信化删除，见 ADR-0021。</p>
 *
 * <p><b>启动期校验</b>：{@code provider=aliyun} 时若任一凭证或任一场景模板为空，直接抛
 * {@link IllegalStateException} 让进程启动失败 —— 不允许「配置漏了但静默降级成 mock」，
 * 否则线上会出现「用户点了发送、系统返回成功、短信永远不来」的哑故障。
 * {@code provider=mock} 不校验凭证（开发 / 测试 / e2e 用），但 dev 配置仍会给
 * 假模板 Code，避免 mock 通道下模板为空。</p>
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

    /**
     * 模板 Code 路由表：键为 {@link SmsScene#getKey()}（当前仅 missed-dose）。
     *
     * <p>不再保留单 {@code templateCode} 字段，也不做兼容层 —— 一处配置只表达一件事，
     * 避免「验证码模板被拿去发漏服提醒」这类静默错配。</p>
     */
    private Map<String, String> templates = new HashMap<>(4);

    /** 静默时段：默认 22:00–08:00，可开关 */
    private QuietHours quietHours = new QuietHours();

    /** 重试与死信策略 */
    private Retry retry = new Retry();

    /** 频控：防止同一位家属被多个漏服任务反复轰炸 */
    private Frequency frequency = new Frequency();

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
            // 两个场景都必须单独配置；缺任一即拒绝启动，避免「验证码能发、漏服短信发不出去」
            for (SmsScene scene : SmsScene.values()) {
                requireText(templates.get(scene.getKey()),
                        "nianglin.sms.templates[" + scene.getKey() + "]（环境变量 " + scene.getEnvVar() + "）");
            }
        }
        validateQuietHours();
        validateRetry();
        validateFrequency();
        log.info("短信通道配置已加载 | provider={} | scenes={} | quietHours={}~{} | maxAttempts={}",
                normalized, templates.keySet(), quietHours.getStart(), quietHours.getEnd(),
                retry.getMaxAttempts());
    }

    private void validateQuietHours() {
        LocalTime start = parseTime(quietHours.getStart(), "nianglin.sms.quiet-hours.start");
        LocalTime end = parseTime(quietHours.getEnd(), "nianglin.sms.quiet-hours.end");
        if (start.equals(end)) {
            throw new IllegalStateException(
                    "nianglin.sms.quiet-hours.start 与 end 不能相同（零长度静默窗口没有业务含义）。");
        }
        quietHours.setStartTime(start);
        quietHours.setEndTime(end);
    }

    private void validateRetry() {
        if (retry.getMaxAttempts() < 1) {
            throw new IllegalStateException("nianglin.sms.retry.max-attempts 必须 ≥ 1。");
        }
        if (retry.getBaseDelayMs() < 1 || retry.getMaxDelayMs() < retry.getBaseDelayMs()) {
            throw new IllegalStateException(
                    "nianglin.sms.retry 退避配置不合法：base-delay-ms ≥ 1 且 max-delay-ms ≥ base-delay-ms。");
        }
        if (retry.getBatchSize() < 1) {
            throw new IllegalStateException("nianglin.sms.retry.batch-size 必须 ≥ 1。");
        }
    }

    private void validateFrequency() {
        if (frequency.getMissedDoseDailyCap() < 1) {
            throw new IllegalStateException(
                    "nianglin.sms.frequency.missed-dose-daily-cap 必须 ≥ 1（0 或负数会让所有漏服短信被静默拦截）。");
        }
    }

    private static LocalTime parseTime(String value, String name) {
        try {
            return LocalTime.parse(value, DateTimeFormatter.ofPattern("HH:mm"));
        } catch (DateTimeParseException | NullPointerException e) {
            throw new IllegalStateException(name + " 必须为 HH:mm 格式，当前为：" + value);
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "nianglin.sms.provider=aliyun 时必须配置 " + name + "；未配置将拒绝启动，不静默降级为 mock。");
        }
    }

    /** 静默时段配置 */
    @Data
    public static class QuietHours {
        /** 总开关；关掉后任何时刻都立即发送 */
        private boolean enabled = true;
        /** 静默开始时刻（HH:mm，默认 22:00） */
        private String start = "22:00";
        /** 静默结束时刻（HH:mm，默认次日 08:00） */
        private String end = "08:00";
        /** 解析后的开始时刻，不参与配置绑定 */
        private LocalTime startTime;
        /** 解析后的结束时刻，不参与配置绑定 */
        private LocalTime endTime;
    }

    /** 频控配置 */
    @Data
    public static class Frequency {
        /**
         * 单用户单日漏服短信上限（默认 3）。
         *
         * <p>按「用户 + 场景 + 当日已入队/已发送行数」计数，与任务数无关：
         * 同一位家属当天第 N+1 条漏服短信直接跳过，但站内信仍照发。
         * 环境变量：{@code NIANGLIN_SMS_FREQUENCY_MISSEDDOSEDAILYCAP}。</p>
         */
        private int missedDoseDailyCap = 3;
    }

    /** 重试与死信配置 */
    @Data
    public static class Retry {
        /** 最大尝试次数，达到后进入 DEAD */
        private int maxAttempts = 3;
        /** 首次失败后的基础退避（毫秒），之后按 2^(n-1) 递增 */
        private long baseDelayMs = 60_000;
        /** 单次退避上限（毫秒） */
        private long maxDelayMs = 3_600_000;
        /** 重试扫描间隔（毫秒） */
        private long scanIntervalMs = 60_000;
        /** 单轮扫描最多处理多少条 */
        private int batchSize = 100;
    }
}

package org.company.nianglin.constant;

import lombok.Getter;

/**
 * 短信模板场景（V4 · P1-3）。
 *
 * <p>一个场景对应一个模板 Code，配置键与配置项键一一对应：
 * {@code MISSED_DOSE → nianglin.sms.templates.missed-dose}。
 * 原 {@code VERIFICATION}（找回密码验证码）场景已随去短信化删除，见 ADR-0021。</p>
 *
 * @author 银龄伴诊团队
 * @since V4 · P1-3
 */
@Getter
public enum SmsScene {

    /** 漏服站外提醒（只含中性变量，不含剂量 / 诊断 / 建议） */
    MISSED_DOSE("missed-dose", "ALIYUN_SMS_TEMPLATE_CODE_MISSED_DOSE");

    /** {@code nianglin.sms.templates} 下的配置键 */
    private final String key;

    /** 生产环境变量名，用于 fail-fast 信息里精确指出缺哪个 */
    private final String envVar;

    SmsScene(String key, String envVar) {
        this.key = key;
        this.envVar = envVar;
    }
}

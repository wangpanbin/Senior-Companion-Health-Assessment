package org.company.nianglin.support;

import org.company.nianglin.config.SmsProperties;

/**
 * 单测用的短信配置工厂（V4 · P1-3）。
 *
 * <p>mock 通道本身不校验模板，但 {@code SmsTemplateRouter} 会检查 Code 非空；
 * 这里给两个场景各一个假 Code，避免每个测试重复拼装。</p>
 */
public final class TestSmsProperties {

    private TestSmsProperties() {
    }

    public static SmsProperties forMock() {
        SmsProperties properties = new SmsProperties();
        properties.setProvider(SmsProperties.PROVIDER_MOCK);
        properties.getTemplates().put("verification", "SMS_DEV_VERIFICATION");
        properties.getTemplates().put("missed-dose", "SMS_DEV_MISSED_DOSE");
        return properties;
    }
}

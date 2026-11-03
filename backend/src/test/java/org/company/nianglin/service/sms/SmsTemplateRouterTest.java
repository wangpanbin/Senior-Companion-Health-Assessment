package org.company.nianglin.service.sms;

import org.company.nianglin.config.SmsProperties;
import org.company.nianglin.constant.SmsScene;
import org.company.nianglin.support.TestSmsProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 短信模板路由测试（V4 · P1-3）。
 *
 * <p>锁两件事：两个场景各自解析到正确的模板 Code；aliyun 下缺任一模板
 * 必须在启动期失败，而不是等到用户点「发送」才静默返回成功。</p>
 */
@DisplayName("短信模板：场景路由与启动期 fail-fast")
class SmsTemplateRouterTest {

    @Test
    @DisplayName("verification / missed-dose 各自解析到自己的模板 Code")
    void shouldResolveEachSceneIndependently() {
        SmsProperties properties = TestSmsProperties.forMock();
        properties.getTemplates().put("verification", "TPL_VERIFY");
        properties.getTemplates().put("missed-dose", "TPL_MISSED");
        SmsTemplateRouter router = new SmsTemplateRouter(properties);

        assertEquals("TPL_VERIFY", router.resolve(SmsScene.VERIFICATION));
        assertEquals("TPL_MISSED", router.resolve(SmsScene.MISSED_DOSE));
    }

    @Test
    @DisplayName("某场景模板为空 → resolve 抛出异常且信息含配置键与环境变量")
    void shouldRejectBlankTemplateAtRuntime() {
        SmsProperties properties = TestSmsProperties.forMock();
        properties.getTemplates().put("verification", "TPL_VERIFY");
        SmsTemplateRouter router = new SmsTemplateRouter(properties);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> router.resolve(SmsScene.MISSED_DOSE));
        assertTrue(error.getMessage().contains("missed-dose"));
        assertTrue(error.getMessage().contains("ALIYUN_SMS_TEMPLATE_CODE_MISSED_DOSE"));
    }

    @Test
    @DisplayName("provider=aliyun 缺 verification 模板 → 启动期失败")
    void shouldFailFastWhenVerificationTemplateMissing() {
        SmsProperties properties = TestSmsProperties.forMock();
        properties.setProvider(SmsProperties.PROVIDER_ALIYUN);
        properties.setAccessKeyId("placeholder");
        properties.setAccessKeySecret("placeholder");
        properties.setSignName("银龄伴诊");
        properties.getTemplates().remove("verification");

        IllegalStateException error = assertThrows(IllegalStateException.class, properties::validate);
        assertTrue(error.getMessage().contains("verification"));
        assertTrue(error.getMessage().contains("ALIYUN_SMS_TEMPLATE_CODE_VERIFICATION"));
    }

    @Test
    @DisplayName("provider=mock 不要求模板非空（dev 另有假 Code，但校验不应依赖它）")
    void mockProviderDoesNotRequireTemplates() {
        SmsProperties properties = new SmsProperties();
        properties.setProvider(SmsProperties.PROVIDER_MOCK);
        properties.getTemplates().clear();
        properties.validate();
        assertEquals(SmsProperties.PROVIDER_MOCK, properties.getProvider());
    }
}

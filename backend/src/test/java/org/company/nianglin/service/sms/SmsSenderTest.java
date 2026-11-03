package org.company.nianglin.service.sms;

import org.company.nianglin.config.SmsProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 短信通道的纯函数 / 启动期校验测试（V4 · P1-1）。
 *
 * <p>不联网、不需要阿里云凭证：只锁定两件事 ——</p>
 * <ol>
 *   <li>RFC 3986 percent-encode 与阿里云要求一致（空格 %20、* → %2A、~ 不编码、
 *       中文按 UTF-8 编码）——这是签名正确性的地基；</li>
 *   <li>{@code provider=aliyun} 缺凭证必须 fail-fast，{@code mock} 不校验。</li>
 * </ol>
 *
 * @author 银龄伴诊团队
 * @since V4 · P1-1
 */
@DisplayName("短信通道：签名编码与启动期校验")
class SmsSenderTest {

    @Test
    @DisplayName("percent-encode 符合阿里云 RPC 要求")
    void shouldPercentEncodeForAliyunRpc() {
        assertEquals("%20", AliyunSmsSender.percentEncode(" "));
        assertEquals("%2A", AliyunSmsSender.percentEncode("*"));
        assertEquals("~", AliyunSmsSender.percentEncode("~"));
        assertEquals("%2F", AliyunSmsSender.percentEncode("/"));
        assertEquals("a%20b%2Ac", AliyunSmsSender.percentEncode("a b*c"));
        // 中文签名 / 模板参数必须按 UTF-8 百分号编码，否则签名必然对不上
        assertEquals("%E9%93%B6%E9%BE%84", AliyunSmsSender.percentEncode("银龄"));
    }

    @Test
    @DisplayName("provider=aliyun 缺任一凭证 → 启动期 IllegalStateException")
    void shouldFailFastWhenAliyunCredentialsMissing() {
        SmsProperties properties = new SmsProperties();
        properties.setProvider(SmsProperties.PROVIDER_ALIYUN);
        properties.setAccessKeyId("placeholder");
        properties.setAccessKeySecret("placeholder");
        properties.setSignName("银龄伴诊");
        properties.getTemplates().put("verification", "SMS_TPL_VERIFY");
        // missed-dose 特意留空
        IllegalStateException error = assertThrows(IllegalStateException.class, properties::validate);
        assertTrue(error.getMessage().contains("missed-dose"), "提示必须指明缺失的场景键");
        assertTrue(error.getMessage().contains("ALIYUN_SMS_TEMPLATE_CODE_MISSED_DOSE"),
                "提示必须指明对应的环境变量");
    }

    @Test
    @DisplayName("provider=mock 不校验凭证；未知 provider 拒绝启动")
    void shouldOnlyValidateKnownProvider() {
        SmsProperties mock = new SmsProperties();
        mock.setProvider(SmsProperties.PROVIDER_MOCK);
        assertDoesNotThrow(mock::validate);

        SmsProperties unknown = new SmsProperties();
        unknown.setProvider("tencent");
        assertThrows(IllegalStateException.class, unknown::validate);
    }
}

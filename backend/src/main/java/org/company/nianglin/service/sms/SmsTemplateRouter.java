package org.company.nianglin.service.sms;

import lombok.RequiredArgsConstructor;
import org.company.nianglin.config.SmsProperties;
import org.company.nianglin.constant.SmsScene;
import org.springframework.stereotype.Component;

/**
 * 短信模板路由（V4 · P1-3）。
 *
 * <p>业务代码只表达「我要发哪种场景」，由本类解析成具体模板 Code。
 * 这样做避免了两种错配：</p>
 * <ol>
 *   <li>找回密码与漏服提醒共用单 {@code templateCode}，一旦改了其中一个场景，
 *       另一个场景会静默用错模板；</li>
 *   <li>调用方各自 {@code properties.getTemplates().get(...)}，键名写错时在
 *       运行期才发现，而不是启动期。</li>
 * </ol>
 *
 * @author 银龄伴诊团队
 * @since V4 · P1-3
 */
@Component
@RequiredArgsConstructor
public class SmsTemplateRouter {

    private final SmsProperties properties;

    /**
     * 解析场景对应的模板 Code。
     *
     * @throws IllegalStateException 场景模板未配置（aliyun 启动期已 fail-fast，
     *                               此异常只会在 mock 下配置漏写时出现）
     */
    public String resolve(SmsScene scene) {
        String code = properties.getTemplates().get(scene.getKey());
        if (code == null || code.isBlank()) {
            throw new IllegalStateException(
                    "短信模板未配置 | scene=" + scene.getKey()
                            + " | 请配置 nianglin.sms.templates[" + scene.getKey() + "]"
                            + "（环境变量 " + scene.getEnvVar() + "）");
        }
        return code;
    }
}

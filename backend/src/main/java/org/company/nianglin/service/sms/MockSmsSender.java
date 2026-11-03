package org.company.nianglin.service.sms;

import lombok.extern.slf4j.Slf4j;
import org.company.nianglin.util.MaskUtil;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * mock 短信通道（开发 / 测试 / e2e）。
 *
 * <p>只打印脱敏手机号与「已模拟发送」，<b>不打印验证码</b>：
 * 验证码存在 Redis {@code sms:reset:{phone}} 里，e2e 脚本直接读 Redis，
 * 不走日志这条会泄露凭证的旁路。</p>
 *
 * @author 银龄伴诊团队
 * @since V4 · P1-1
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "nianglin.sms.provider", havingValue = "mock", matchIfMissing = false)
public class MockSmsSender implements SmsSender {

    @Override
    public void send(String phone, String templateCode, Map<String, String> params) {
        log.info("模拟短信发送 | phone={} | templateCode={} | 已模拟发送（验证码不落日志，e2e 请读 Redis）",
                MaskUtil.phone(phone), templateCode);
    }
}

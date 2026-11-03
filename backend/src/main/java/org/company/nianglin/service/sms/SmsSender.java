package org.company.nianglin.service.sms;

import java.util.Map;

/**
 * 短信发送通道（V4 · P1-1）。
 *
 * <p>业务层只依赖本接口，具体实现由 {@code nianglin.sms.provider} 决定：
 * {@code mock}（开发 / 测试 / e2e，仅日志）或 {@code aliyun}（Dysmsapi RPC）。
 * 切换通道不需要改业务代码，也不会改变对外契约。</p>
 *
 * <p><b>实现约定</b>：</p>
 * <ul>
 *   <li>参数 {@code params} 只放模板占位符（本任务只放 {@code code}），不要拼接额外文案；</li>
 *   <li>发送失败应抛出 {@code BusinessException}，由调用方统一吞掉并记录
 *       —— 对外响应必须与「发送成功」完全同构，避免账号存在性侧信道；</li>
 *   <li><b>禁止</b>把验证码写进日志。</li>
 * </ul>
 *
 * @author 银龄伴诊团队
 * @since V4 · P1-1
 */
public interface SmsSender {

    /**
     * 发送模板短信。
     *
     * @param phone        接收手机号（11 位）
     * @param templateCode 模板 Code（mock 通道可忽略）
     * @param params       模板参数，例如 {@code {"code": "123456"}}
     */
    void send(String phone, String templateCode, Map<String, String> params);
}

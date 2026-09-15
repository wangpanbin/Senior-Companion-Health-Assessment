package org.company.nianglin.service.impl;

import cn.hutool.captcha.CaptchaUtil;
import cn.hutool.captcha.LineCaptcha;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.company.nianglin.common.ResultCode;
import org.company.nianglin.constant.RedisKeyConstants;
import org.company.nianglin.exception.BusinessException;
import org.company.nianglin.security.SecurityProperties;
import org.company.nianglin.service.CaptchaService;
import org.company.nianglin.vo.CaptchaVO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Locale;
import java.util.UUID;

/**
 * 图形验证码实现。
 *
 * <p>选型说明：图形验证码用 <b>Hutool 的 {@code LineCaptcha}</b>（已随 {@code hutool-all} 引入，
 * 未新增依赖），不使用 Google Kaptcha —— 后者已多年不维护，且会额外拉一个 jar。</p>
 *
 * <p><b>为什么验证码必须放 Redis 而不是 Session</b>：本服务是无状态的，
 * 不创建 Session；把验证码明文放 Redis 并设 TTL，既不破坏无状态特性，
 * 又能让多实例共享同一份验证码。</p>
 *
 * @author 银龄伴诊团队
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CaptchaServiceImpl implements CaptchaService {

    /** 图片宽度（px），与登录页 {@code .login__captcha-img} 的 120×40 保持一致 */
    private static final int WIDTH = 120;

    /** 图片高度（px） */
    private static final int HEIGHT = 40;

    /** 字符个数 */
    private static final int CODE_COUNT = 4;

    /** 干扰线条数：过多会让老人看不清，20 是「机器难识别 / 人眼可识别」的平衡点 */
    private static final int LINE_COUNT = 20;

    /** base64 图片前缀，前端可直接放进 <img :src> */
    private static final String DATA_URI_PREFIX = "data:image/png;base64,";

    private final StringRedisTemplate redisTemplate;
    private final SecurityProperties securityProperties;

    @Override
    public CaptchaVO generate() {
        LineCaptcha captcha = CaptchaUtil.createLineCaptcha(WIDTH, HEIGHT, CODE_COUNT, LINE_COUNT);
        String captchaKey = UUID.randomUUID().toString().replace("-", "");
        long expireSeconds = securityProperties.getCaptchaExpireSeconds();

        // 统一存大写，比对时把用户输入也转大写 —— 老人输入验证码时分不清大小写，
        // 「明明输对了却提示错误」是适老化场景里最招人烦的体验问题之一
        redisTemplate.opsForValue().set(
                RedisKeyConstants.captcha(captchaKey),
                captcha.getCode().toUpperCase(Locale.ROOT),
                Duration.ofSeconds(expireSeconds));

        return new CaptchaVO()
                .setCaptchaKey(captchaKey)
                .setCaptchaImage(DATA_URI_PREFIX + captcha.getImageBase64())
                .setExpiresIn(expireSeconds);
    }

    @Override
    public void validate(String captchaKey, String captchaCode) {
        if (captchaKey == null || captchaKey.isBlank() || captchaCode == null || captchaCode.isBlank()) {
            throw new BusinessException(ResultCode.CAPTCHA_ERROR);
        }

        // getAndDelete 是「取出即删除」的原子操作：无论校验成功还是失败，验证码都已消耗，
        // 因此同一 captchaKey 连续提交两次，第二次必然拿不到值而失败（M2 验收项）
        String expected = redisTemplate.opsForValue().getAndDelete(RedisKeyConstants.captcha(captchaKey));
        if (expected == null || !expected.equals(captchaCode.trim().toUpperCase(Locale.ROOT))) {
            // 不区分「已过期」与「填错了」，避免给暴力破解者反馈信号
            log.debug("验证码校验失败 | key={}", captchaKey);
            throw new BusinessException(ResultCode.CAPTCHA_ERROR);
        }
    }
}

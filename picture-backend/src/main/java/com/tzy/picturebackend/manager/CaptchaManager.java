package com.tzy.picturebackend.manager;

import cn.hutool.captcha.CaptchaUtil;
import cn.hutool.captcha.ShearCaptcha;
import cn.hutool.core.util.StrUtil;
import com.tzy.picturebackend.exception.BusinessException;
import com.tzy.picturebackend.exception.ErrorCode;
import com.tzy.picturebackend.model.vo.CaptchaVO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Locale;
import java.util.UUID;

/**
 * 图形验证码：答案存在 Redis，多实例部署时也能校验。
 * <p>
 * 验证码一次性使用：无论校验成功还是失败都会删除，避免同一个验证码被暴力尝试；
 * 5 分钟未使用自动过期。图片由 Hutool 生成，答案不写日志、不放进 Session。
 */
@Component
public class CaptchaManager {

    private static final String KEY_PREFIX = "picture:captcha:";

    private static final Duration TTL = Duration.ofMinutes(5);

    /**
     * 去掉容易混淆字符的 4 位字母数字验证码
     */
    private static final int WIDTH = 160;

    private static final int HEIGHT = 60;

    private static final int CODE_COUNT = 4;

    private static final int SHEAR_THICKNESS = 4;

    private final StringRedisTemplate redisTemplate;

    public CaptchaManager(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 生成一个新的验证码，同时把答案写入 Redis
     */
    public CaptchaVO generate() {
        ShearCaptcha captcha = CaptchaUtil.createShearCaptcha(
                WIDTH, HEIGHT, CODE_COUNT, SHEAR_THICKNESS);
        String captchaId = UUID.randomUUID().toString().replace("-", "");
        redisTemplate.opsForValue().set(
                KEY_PREFIX + captchaId, captcha.getCode().toLowerCase(Locale.ROOT), TTL);

        CaptchaVO captchaVO = new CaptchaVO();
        captchaVO.setCaptchaId(captchaId);
        captchaVO.setImage(captcha.getImageBase64Data());
        captchaVO.setExpiresInSeconds(TTL.toSeconds());
        return captchaVO;
    }

    /**
     * 校验并消费验证码
     *
     * @param captchaId   验证码 id
     * @param captchaCode 用户输入的验证码
     */
    public void verify(String captchaId, String captchaCode) {
        if (StrUtil.isBlank(captchaId) || StrUtil.isBlank(captchaCode)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "验证码不能为空");
        }
        // GETDEL 保证「读取 + 删除」原子完成，并发请求也只能有一次校验机会
        String expected = redisTemplate.opsForValue().getAndDelete(KEY_PREFIX + captchaId);
        if (expected == null || !expected.equals(captchaCode.trim().toLowerCase(Locale.ROOT))) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "验证码错误或已过期");
        }
    }
}

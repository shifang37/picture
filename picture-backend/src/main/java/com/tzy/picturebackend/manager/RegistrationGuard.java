package com.tzy.picturebackend.manager;

import cn.hutool.crypto.digest.DigestUtil;
import com.tzy.picturebackend.exception.BusinessException;
import com.tzy.picturebackend.metrics.PictureMetrics;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Locale;

/**
 * 注册防护：先校验图形验证码，再按账号维度限流。
 * <p>
 * IP 维度由 {@code RateLimitFilter} 在读取请求体之前完成，这里补账号维度，
 * 防止同一账号被反复注册/探测（账号在数据库中是大小写不敏感的，因此统一转小写）。
 * 先验证码、后账号限流：验证码输错不消耗账号额度，避免误伤正常用户。
 */
@Component
public class RegistrationGuard {

    /**
     * 同一账号 10 分钟内最多 3 次有效注册请求
     */
    static final int ACCOUNT_LIMIT = 3;

    static final Duration ACCOUNT_WINDOW = Duration.ofMinutes(10);

    private final CaptchaManager captchaManager;

    private final RateLimitManager rateLimitManager;

    private final PictureMetrics pictureMetrics;

    public RegistrationGuard(CaptchaManager captchaManager, RateLimitManager rateLimitManager,
                             PictureMetrics pictureMetrics) {
        this.captchaManager = captchaManager;
        this.rateLimitManager = rateLimitManager;
        this.pictureMetrics = pictureMetrics;
    }

    /**
     * 注册前置校验
     *
     * @param userAccount 账号
     * @param captchaId   验证码 id
     * @param captchaCode 验证码
     */
    public void check(String userAccount, String captchaId, String captchaCode) {
        captchaManager.verify(captchaId, captchaCode);
        try {
            rateLimitManager.check(accountKey(userAccount), ACCOUNT_LIMIT, ACCOUNT_WINDOW);
        } catch (BusinessException e) {
            pictureMetrics.rateLimited("register");
            throw e;
        }
    }

    /**
     * 账号维度的限流键。账号可能包含任意字符，先做 SHA-256 再进 Redis，
     * 既避免特殊字符污染 key，也不把账号明文写进限流数据。
     */
    static String accountKey(String userAccount) {
        String normalized = userAccount == null ? "" : userAccount.trim().toLowerCase(Locale.ROOT);
        return "register:account:" + DigestUtil.sha256Hex(normalized);
    }
}

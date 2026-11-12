package com.tzy.picturebackend.manager;

import com.tzy.picturebackend.exception.BusinessException;
import com.tzy.picturebackend.exception.ErrorCode;
import com.tzy.picturebackend.metrics.PictureMetrics;
import com.tzy.picturebackend.support.InMemoryRedis;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 注册防护测试：验证码校验 + 账号维度限流
 */
class RegistrationGuardTest {

    private CaptchaManager captchaManager;

    private SimpleMeterRegistry meterRegistry;

    private RegistrationGuard registrationGuard;

    @BeforeEach
    void setUp() {
        captchaManager = mock(CaptchaManager.class);
        RateLimitManager rateLimitManager = new RateLimitManager(new InMemoryRateLimitCounter());
        meterRegistry = new SimpleMeterRegistry();
        registrationGuard = new RegistrationGuard(captchaManager, rateLimitManager,
                new PictureMetrics(meterRegistry));
    }

    @Test
    @DisplayName("通过：先校验验证码，再按账号计数")
    void check_verifiesCaptchaAndCountsAccount() {
        registrationGuard.check("tzyy", "captcha-id", "a1b2");

        verify(captchaManager).verify("captcha-id", "a1b2");
    }

    @Test
    @DisplayName("限流：同一账号第 4 次有效请求返回 42900，并记录 register 指标")
    void check_blocksAfterAccountLimit() {
        registrationGuard.check("tzyy", "id", "code");
        registrationGuard.check("tzyy", "id", "code");
        registrationGuard.check("tzyy", "id", "code");

        BusinessException e = assertThrows(BusinessException.class,
                () -> registrationGuard.check("tzyy", "id", "code"));
        assertEquals(ErrorCode.TOO_MANY_REQUESTS.getCode(), e.getCode());
        assertEquals(1.0, meterRegistry.get(PictureMetrics.RATE_LIMITED)
                .tag("type", "register").counter().count());
    }

    @Test
    @DisplayName("限流键：账号大小写与首尾空格不敏感，且不包含账号明文")
    void accountKey_isNormalizedAndHashed() {
        String upper = RegistrationGuard.accountKey(" TZYY ");
        String lower = RegistrationGuard.accountKey("tzyy");

        assertEquals(lower, upper);
        assertFalse(lower.contains("tzyy"), "限流键不应包含账号明文");
        assertNotEquals(lower, RegistrationGuard.accountKey("other"));
    }
}

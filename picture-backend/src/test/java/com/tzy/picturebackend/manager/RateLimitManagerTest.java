package com.tzy.picturebackend.manager;

import com.tzy.picturebackend.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 固定窗口限流器测试
 */
class RateLimitManagerTest {

    private RateLimitManager rateLimitManager;

    @BeforeEach
    void setUp() {
        rateLimitManager = new RateLimitManager();
    }

    @Test
    @DisplayName("限流：窗口内超过限制应抛出 42900")
    void check_throwsWhenLimitExceeded() {
        String key = "login:ip:127.0.0.1";
        assertDoesNotThrow(() -> rateLimitManager.check(key, 2, Duration.ofMinutes(1)));
        assertDoesNotThrow(() -> rateLimitManager.check(key, 2, Duration.ofMinutes(1)));

        BusinessException e = assertThrows(BusinessException.class,
                () -> rateLimitManager.check(key, 2, Duration.ofMinutes(1)));
        assertEquals(42900, e.getCode());
    }

    @Test
    @DisplayName("限流：不同 key 互不影响")
    void check_keepsKeysIndependent() {
        assertDoesNotThrow(() -> rateLimitManager.check("key-a", 1, Duration.ofMinutes(1)));
        assertDoesNotThrow(() -> rateLimitManager.check("key-b", 1, Duration.ofMinutes(1)));
    }

    @Test
    @DisplayName("限流：reset 后重新计数")
    void reset_clearsCounters() {
        rateLimitManager.check("key", 1, Duration.ofMinutes(1));
        rateLimitManager.reset();

        assertDoesNotThrow(() -> rateLimitManager.check("key", 1, Duration.ofMinutes(1)));
    }
}

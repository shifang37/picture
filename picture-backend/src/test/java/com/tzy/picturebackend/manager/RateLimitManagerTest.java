package com.tzy.picturebackend.manager;

import com.tzy.picturebackend.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 固定窗口限流器测试（内存计数器，不依赖 Redis）
 */
class RateLimitManagerTest {

    private InMemoryRateLimitCounter counter;

    private RateLimitManager rateLimitManager;

    @BeforeEach
    void setUp() {
        counter = new InMemoryRateLimitCounter();
        rateLimitManager = new RateLimitManager(counter);
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
        counter.reset();

        assertDoesNotThrow(() -> rateLimitManager.check("key", 1, Duration.ofMinutes(1)));
    }

    @Test
    @DisplayName("限流：计数器返回 0 时视为放行，不让限流器自己制造故障")
    void check_allowsWhenCounterDegrades() {
        RateLimitManager degraded = new RateLimitManager((key, window) -> 0L);
        AtomicLong calls = new AtomicLong();

        assertDoesNotThrow(() -> {
            for (int i = 0; i < 5; i++) {
                degraded.check("key", 1, Duration.ofMinutes(1));
                calls.incrementAndGet();
            }
        });
        assertEquals(5, calls.get());
    }
}

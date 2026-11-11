package com.tzy.picturebackend.manager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Redis 限流计数器测试：脚本参数与 Redis 故障时的降级行为
 */
class RedisRateLimitCounterTest {

    private StringRedisTemplate redisTemplate;

    private RedisRateLimitCounter counter;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        counter = new RedisRateLimitCounter(redisTemplate);
    }

    @Test
    @DisplayName("计数：使用 picture:ratelimit: 前缀，并把窗口毫秒数传给 Lua 脚本")
    @SuppressWarnings("unchecked")
    void increment_usesNamespacedKeyAndWindowMillis() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any())).thenReturn(3L);

        long count = counter.increment("login:ip:10.0.0.1", Duration.ofMinutes(1));

        assertEquals(3L, count);
        verify(redisTemplate).execute(any(RedisScript.class),
                eq(List.of("picture:ratelimit:login:ip:10.0.0.1")), eq("60000"));
    }

    @Test
    @DisplayName("降级：Redis 不可用时返回 0（放行），不抛异常")
    @SuppressWarnings("unchecked")
    void increment_allowsWhenRedisFails() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any()))
                .thenThrow(new RedisConnectionFailureException("connection refused"));

        assertEquals(0L, counter.increment("upload:user:1", Duration.ofMinutes(1)));
    }

    @Test
    @DisplayName("降级：脚本返回 null 时按 0 处理")
    @SuppressWarnings("unchecked")
    void increment_treatsNullAsZero() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any())).thenReturn(null);

        assertEquals(0L, counter.increment("upload:user:1", Duration.ofMinutes(1)));
    }
}

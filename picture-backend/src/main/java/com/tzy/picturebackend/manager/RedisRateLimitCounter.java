package com.tzy.picturebackend.manager;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collections;

/**
 * 基于 Redis 的固定窗口计数器，语义与内存实现一致：窗口内第一次自增时设置过期时间。
 * <p>
 * INCR 和 PEXPIRE 用 Lua 脚本保证原子性，避免两条命令之间进程退出导致计数键永不过期。
 */
@Component
@Slf4j
public class RedisRateLimitCounter implements RateLimitCounter {

    private static final String KEY_PREFIX = "picture:ratelimit:";

    private static final RedisScript<Long> FIXED_WINDOW_SCRIPT = new DefaultRedisScript<>(
            "local c = redis.call('INCR', KEYS[1])\n"
                    + "if c == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end\n"
                    + "return c",
            Long.class);

    private final StringRedisTemplate redisTemplate;

    public RedisRateLimitCounter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public long increment(String key, Duration window) {
        try {
            Long count = redisTemplate.execute(FIXED_WINDOW_SCRIPT,
                    Collections.singletonList(KEY_PREFIX + key),
                    String.valueOf(window.toMillis()));
            return count == null ? 0L : count;
        } catch (RuntimeException e) {
            // Redis 不可用时会话本身也不可用，登录/上传本来就成功不了；
            // 限流器不再叠加一层故障，放行并记录日志便于排查。
            log.error("Redis 限流计数失败，本次请求放行, key = {}", key, e);
            return 0L;
        }
    }
}

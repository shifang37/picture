package com.tzy.picturebackend.manager;

import com.tzy.picturebackend.exception.BusinessException;
import com.tzy.picturebackend.exception.ErrorCode;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 固定窗口限流器：把计数交给 {@link RateLimitCounter} 实现。
 * <p>
 * 生产环境注入 {@link RedisRateLimitCounter}，多实例共享计数；
 * 单元测试使用 {@link InMemoryRateLimitCounter}，不依赖外部服务。
 */
@Component
public class RateLimitManager {

    private final RateLimitCounter counter;

    public RateLimitManager(RateLimitCounter counter) {
        this.counter = counter;
    }

    /**
     * 检查某个 key 在窗口内是否超过限制，超过时抛出 42900
     *
     * @param key    限流键，例如 login:ip:127.0.0.1
     * @param limit  窗口内允许的最大请求数
     * @param window 时间窗口
     */
    public void check(String key, int limit, Duration window) {
        long count = counter.increment(key, window);
        if (count > limit) {
            throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS);
        }
    }
}

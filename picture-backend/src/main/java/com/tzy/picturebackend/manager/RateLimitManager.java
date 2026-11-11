package com.tzy.picturebackend.manager;

import com.tzy.picturebackend.exception.BusinessException;
import com.tzy.picturebackend.exception.ErrorCode;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于内存的固定窗口限流器。
 * <p>
 * 当前部署为单实例，内存计数足够；多实例部署时应替换为 Redis 等共享存储。
 */
@Component
public class RateLimitManager {

    /**
     * 超过该数量时触发一次过期键清理，避免恶意 IP 导致 Map 无限增长
     */
    private static final int CLEANUP_THRESHOLD = 10_000;

    private final Map<String, Counter> counters = new ConcurrentHashMap<>();

    /**
     * 检查某个 key 在窗口内是否超过限制，超过时抛出 42900
     *
     * @param key    限流键，例如 login:ip:127.0.0.1
     * @param limit  窗口内允许的最大请求数
     * @param window 时间窗口
     */
    public void check(String key, int limit, Duration window) {
        long now = System.currentTimeMillis();
        Counter counter = counters.compute(key, (k, old) -> {
            if (old == null || now - old.windowStart >= window.toMillis()) {
                return new Counter(now);
            }
            old.count++;
            return old;
        });
        if (counter.count > limit) {
            throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS);
        }
        if (counters.size() > CLEANUP_THRESHOLD) {
            cleanup(now, window.toMillis());
        }
    }

    /**
     * 清空计数，仅用于测试或运维手动重置
     */
    public void reset() {
        counters.clear();
    }

    private void cleanup(long now, long windowMillis) {
        counters.entrySet().removeIf(entry -> now - entry.getValue().windowStart >= windowMillis);
    }

    private static final class Counter {
        private final long windowStart;
        private int count = 1;

        private Counter(long windowStart) {
            this.windowStart = windowStart;
        }
    }
}

package com.tzy.picturebackend.manager;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于 JVM 内存的固定窗口计数器。
 * <p>
 * 只用于单元测试（不依赖 Redis）；生产环境使用 {@link RedisRateLimitCounter}，
 * 这样多实例部署时计数才是共享的。
 */
public class InMemoryRateLimitCounter implements RateLimitCounter {

    /**
     * 超过该数量时触发一次过期键清理，避免恶意 IP 导致 Map 无限增长
     */
    private static final int CLEANUP_THRESHOLD = 10_000;

    private final Map<String, Counter> counters = new ConcurrentHashMap<>();

    @Override
    public long increment(String key, Duration window) {
        long now = System.currentTimeMillis();
        Counter counter = counters.compute(key, (k, old) -> {
            if (old == null || now - old.windowStart >= window.toMillis()) {
                return new Counter(now);
            }
            old.count++;
            return old;
        });
        if (counters.size() > CLEANUP_THRESHOLD) {
            cleanup(now, window.toMillis());
        }
        return counter.count;
    }

    /**
     * 清空计数，仅用于测试
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

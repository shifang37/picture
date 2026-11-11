package com.tzy.picturebackend.manager;

import java.time.Duration;

/**
 * 固定窗口限流计数器。
 * <p>
 * 每次请求在窗口内自增并返回当前计数；实现必须保证「首次计数时设置窗口过期」是原子的，
 * 否则进程崩溃后可能留下永不过期的计数键。
 */
public interface RateLimitCounter {

    /**
     * 在窗口内自增并返回当前计数
     *
     * @param key    业务限流键，例如 login:ip:127.0.0.1
     * @param window 时间窗口
     * @return 当前窗口内的计数；实现选择放行时返回 0
     */
    long increment(String key, Duration window);
}

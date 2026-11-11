package com.tzy.picturebackend.metrics;

import com.github.benmanes.caffeine.cache.Cache;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 业务指标的统一出口。
 * <p>
 * 指标名使用点号分隔，由 Prometheus registry 转成下划线并补 {@code _total} / {@code _seconds}：
 * <ul>
 *   <li>{@code picture.api.errors} → {@code picture_api_errors_total{code}}</li>
 *   <li>{@code picture.rate.limited} → {@code picture_rate_limited_total{type}}</li>
 *   <li>{@code picture.upload} → {@code picture_upload_total{result}}</li>
 *   <li>{@code picture.compress} → {@code picture_compress_seconds}</li>
 *   <li>{@code picture.review.pending} → {@code picture_review_pending}</li>
 *   <li>{@code picture.ai.tokens} → {@code picture_ai_tokens_total{direction}}</li>
 * </ul>
 * <p>
 * 为什么不统计 HTTP 5xx：{@code GlobalExceptionHandler} 与限流响应都返回 HTTP 200 + 业务码，
 * Spring 自带的 {@code http_server_requests} 几乎全是 200，告警只能按业务码计数。
 */
@Component
public class PictureMetrics {

    /**
     * 接口业务错误码计数器（按业务码打标签）
     */
    public static final String API_ERRORS = "picture.api.errors";

    /**
     * 限流计数器（type=login/upload）
     */
    public static final String RATE_LIMITED = "picture.rate.limited";

    /**
     * 上传结果计数器（result=success/failure）
     */
    public static final String UPLOAD = "picture.upload";

    /**
     * 原图压缩计时器
     */
    public static final String COMPRESS = "picture.compress";

    /**
     * 待审核图片数量
     */
    public static final String REVIEW_PENDING = "picture.review.pending";

    /**
     * AI 调用 token 计数（direction=prompt/completion）
     */
    public static final String AI_TOKENS = "picture.ai.tokens";

    private final MeterRegistry registry;

    /**
     * 待审核数量由定时任务刷新，Gauge 只读取这个值，避免抓取时打数据库
     */
    private final AtomicLong reviewPending = new AtomicLong();

    public PictureMetrics(MeterRegistry registry) {
        this.registry = registry;
        Gauge.builder(REVIEW_PENDING, reviewPending, AtomicLong::doubleValue)
                .description("待审核图片数量")
                .register(registry);
    }

    /**
     * 记录一次接口业务错误。HTTP 状态码始终是 200，业务码才是真正的错误信号。
     */
    public void apiError(int code) {
        Counter.builder(API_ERRORS)
                .description("接口业务错误次数")
                .tag("code", String.valueOf(code))
                .register(registry)
                .increment();
    }

    /**
     * 记录一次被限流的请求
     *
     * @param type login / upload
     */
    public void rateLimited(String type) {
        Counter.builder(RATE_LIMITED)
                .description("被限流的请求次数")
                .tag("type", type)
                .register(registry)
                .increment();
    }

    /**
     * 记录一次上传结果
     *
     * @param result success / failure
     */
    public void upload(String result) {
        Counter.builder(UPLOAD)
                .description("图片上传次数")
                .tag("result", result)
                .register(registry)
                .increment();
    }

    /**
     * 原图压缩计时器；调用方用 {@code record(...)} 包住压缩逻辑
     */
    public Timer compressTimer() {
        return Timer.builder(COMPRESS)
                .description("原图压缩耗时")
                // 显式开启直方图桶，Grafana 的 p95 不依赖额外的 metrics 配置
                .publishPercentileHistogram()
                .register(registry);
    }

    /**
     * 更新待审核图片数量
     */
    public void setReviewPending(long count) {
        reviewPending.set(Math.max(0, count));
    }

    /**
     * 记录 AI 调用消耗的 token
     *
     * @param direction prompt / completion
     * @param tokens    本次消耗的 token 数
     */
    public void aiTokens(String direction, long tokens) {
        if (tokens <= 0) {
            return;
        }
        Counter.builder(AI_TOKENS)
                .description("AI 调用消耗的 token 数")
                .tag("direction", direction)
                .register(registry)
                .increment(tokens);
    }

    /**
     * 把 Caffeine 本地缓存的命中率接入 Micrometer（cache_gets_total 等）。
     * <p>
     * 必须显式补上 {@code cache.manager} 与 {@code name} 两个标签：Spring Boot 为
     * RedisCacheManager 注册的 {@code cache.gets} 带有这两个标签，而 Caffeine 默认只带
     * {@code cache}。同一个 Prometheus registry 中同名指标的标签键集合必须一致，
     * 否则其中一方会注册失败，表现为缓存命中率指标缺失并打印 registration failed 警告。
     */
    public void registerCaffeineCache(String cacheName, Cache<?, ?> cache) {
        CaffeineCacheMetrics.monitor(registry, cache, cacheName,
                Tags.of("cache.manager", "caffeine", "name", cacheName));
    }
}

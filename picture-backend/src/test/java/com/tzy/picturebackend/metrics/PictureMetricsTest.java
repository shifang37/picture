package com.tzy.picturebackend.metrics;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 指标名与标签测试：Prometheus 抓取文本必须与应用日志/看板里使用的名字一致
 */
class PictureMetricsTest {

    private PrometheusMeterRegistry registry;

    private PictureMetrics pictureMetrics;

    @BeforeEach
    void setUp() {
        registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        pictureMetrics = new PictureMetrics(registry);
    }

    @Test
    @DisplayName("接口错误：按业务码计数，导出名带 _total 后缀")
    void apiError_countsByCode() {
        pictureMetrics.apiError(50000);
        pictureMetrics.apiError(50000);
        pictureMetrics.apiError(40000);

        String scrape = registry.scrape();
        assertTrue(scrape.contains("picture_api_errors_total"));
        assertTrue(scrape.contains("code=\"50000\""));
        assertEquals(2.0, registry.get(PictureMetrics.API_ERRORS)
                .tag("code", "50000").counter().count());
        assertEquals(1.0, registry.get(PictureMetrics.API_ERRORS)
                .tag("code", "40000").counter().count());
    }

    @Test
    @DisplayName("上传与限流：按结果/类型打标签")
    void uploadAndRateLimit_haveTags() {
        pictureMetrics.upload("success");
        pictureMetrics.upload("failure");
        pictureMetrics.rateLimited("login");

        assertEquals(1.0, registry.get(PictureMetrics.UPLOAD)
                .tag("result", "success").counter().count());
        assertEquals(1.0, registry.get(PictureMetrics.UPLOAD)
                .tag("result", "failure").counter().count());
        assertEquals(1.0, registry.get(PictureMetrics.RATE_LIMITED)
                .tag("type", "login").counter().count());
        String scrape = registry.scrape();
        assertTrue(scrape.contains("picture_upload_total"));
        assertTrue(scrape.contains("picture_rate_limited_total"));
    }

    @Test
    @DisplayName("AI token：只统计正数，按 prompt/completion 分方向")
    void aiTokens_countsPositiveOnly() {
        pictureMetrics.aiTokens("prompt", 120);
        pictureMetrics.aiTokens("completion", 30);
        pictureMetrics.aiTokens("prompt", 0);

        assertEquals(120.0, registry.get(PictureMetrics.AI_TOKENS)
                .tag("direction", "prompt").counter().count());
        assertEquals(30.0, registry.get(PictureMetrics.AI_TOKENS)
                .tag("direction", "completion").counter().count());
        assertTrue(registry.scrape().contains("picture_ai_tokens_total"));
    }

    @Test
    @DisplayName("待审核数量：Gauge 读的是定时任务写入的快照")
    void reviewPending_readsSnapshot() {
        pictureMetrics.setReviewPending(7);

        assertEquals(7.0, registry.get(PictureMetrics.REVIEW_PENDING).gauge().value());
        assertTrue(registry.scrape().contains("picture_review_pending"));
    }

    @Test
    @DisplayName("压缩计时器：导出 picture_compress_seconds")
    void compressTimer_records() {
        pictureMetrics.compressTimer().record(Duration.ofMillis(20));

        assertEquals(1L, registry.get(PictureMetrics.COMPRESS).timer().count());
        String scrape = registry.scrape();
        assertTrue(scrape.contains("picture_compress_seconds_count"));
        assertTrue(scrape.contains("picture_compress_seconds_bucket"), "计时器需要直方图桶才能算 p95");
    }

    @Test
    @DisplayName("Caffeine 缓存：开启 recordStats 后可导出命中次数")
    void caffeineCache_exportsStats() {
        Cache<String, String> cache = Caffeine.newBuilder().recordStats().build();
        pictureMetrics.registerCaffeineCache("test.cache", cache);
        cache.put("k", "v");
        cache.getIfPresent("k");
        cache.getIfPresent("missing");

        String scrape = registry.scrape();
        assertTrue(scrape.contains("cache_gets_total"));
        assertTrue(scrape.contains("cache=\"test.cache\""));
    }
}

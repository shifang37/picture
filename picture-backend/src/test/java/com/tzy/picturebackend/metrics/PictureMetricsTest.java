package com.tzy.picturebackend.metrics;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.Counter;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
    @DisplayName("搜索基线：零结果率按 hit 与 mode 打标签")
    void searchResult_tagsHitAndMode() {
        pictureMetrics.searchResult(true, "es");
        pictureMetrics.searchResult(false, "es");
        pictureMetrics.searchResult(false, "mysql");
        pictureMetrics.searchResult(false, null);

        assertEquals(1.0, registry.get(PictureMetrics.SEARCH_RESULT)
                .tag("hit", "1").tag("mode", "es").counter().count());
        assertEquals(1.0, registry.get(PictureMetrics.SEARCH_RESULT)
                .tag("hit", "0").tag("mode", "es").counter().count());
        assertEquals(1.0, registry.get(PictureMetrics.SEARCH_RESULT)
                .tag("hit", "0").tag("mode", "mysql").counter().count());
        assertEquals(1.0, registry.get(PictureMetrics.SEARCH_RESULT)
                .tag("hit", "0").tag("mode", "unknown").counter().count(),
                "mode 为空时兜底为 unknown，避免出现空标签值");
        String scrape = registry.scrape();
        assertTrue(scrape.contains("picture_search_result_total"));
        assertTrue(scrape.contains("hit=\"0\""));
        assertTrue(scrape.contains("mode=\"mysql\""));
    }

    @Test
    @DisplayName("RAG 向量化：同名 Counter 与 Timer 导出为 _total 与 _seconds")
    void ragEmbed_exportsCounterAndTimer() {
        pictureMetrics.ragEmbed("text", true, 12_000_000L);
        pictureMetrics.ragEmbed("text", false, 3_000_000L);

        assertEquals(1.0, registry.get(PictureMetrics.RAG_EMBED)
                .tag("kind", "text").tag("result", "ok").counter().count());
        assertEquals(1.0, registry.get(PictureMetrics.RAG_EMBED)
                .tag("kind", "text").tag("result", "fail").counter().count());
        assertEquals(2L, registry.get(PictureMetrics.RAG_EMBED)
                .tag("kind", "text").timer().count());

        String scrape = registry.scrape();
        assertTrue(scrape.contains("picture_rag_embed_total"));
        assertTrue(scrape.contains("picture_rag_embed_seconds_count"));
        assertTrue(scrape.contains("picture_rag_embed_seconds_bucket"), "向量化耗时需要直方图算 p95");
    }

    @Test
    @DisplayName("向量索引维护：按动作与结果打标签")
    void ragIndex_tagsActionAndResult() {
        pictureMetrics.ragIndex("write", "ok");
        pictureMetrics.ragIndex("delete", "ok");
        pictureMetrics.ragIndex("reindex", "fail");
        pictureMetrics.ragIndex("guard", "stale");

        assertEquals(1.0, registry.get(PictureMetrics.RAG_INDEX)
                .tag("action", "write").tag("result", "ok").counter().count());
        assertEquals(1.0, registry.get(PictureMetrics.RAG_INDEX)
                .tag("action", "guard").tag("result", "stale").counter().count());
        assertTrue(registry.scrape().contains("picture_rag_index_total"));
    }

    @Test
    @DisplayName("待审核数量：Gauge 读的是定时任务写入的快照")
    void reviewPending_readsSnapshot() {
        pictureMetrics.setReviewPending(7);

        assertEquals(7.0, registry.get(PictureMetrics.REVIEW_PENDING).gauge().value());
        assertTrue(registry.scrape().contains("picture_review_pending"));
    }

    @Test
    @DisplayName("RAG 索引健康度：Gauge 为 1 表示别名不兼容，重复注册不报错")
    void ragIndexStaleGauge_reflectsSnapshot() {
        pictureMetrics.registerRagIndexStaleGauge();
        pictureMetrics.setRagIndexStale(true);

        assertEquals(1.0, registry.get(PictureMetrics.RAG_INDEX_STALE).gauge().value());
        assertTrue(registry.scrape().contains("picture_rag_index_stale"));

        pictureMetrics.setRagIndexStale(false);
        assertEquals(0.0, registry.get(PictureMetrics.RAG_INDEX_STALE).gauge().value());

        // 定时任务每次启动只应注册一次；重复调用必须复用已有 Gauge
        pictureMetrics.registerRagIndexStaleGauge();
        assertEquals(1, registry.find(PictureMetrics.RAG_INDEX_STALE).gauges().size());
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
    @DisplayName("M4 指标：EXIF 处理、秒传命中与配额拒绝都能导出")
    void m4Metrics_exportWithTags() {
        pictureMetrics.exif("rotated");
        pictureMetrics.exif("stripped");
        pictureMetrics.uploadDuplicate();
        pictureMetrics.quotaRejected("storage");
        pictureMetrics.quotaRejected("count");

        String scrape = registry.scrape();
        assertTrue(scrape.contains("picture_exif_total"));
        assertTrue(scrape.contains("picture_upload_duplicate_total"));
        assertTrue(scrape.contains("picture_quota_rejected_total"));
        assertEquals(1.0, registry.get(PictureMetrics.EXIF)
                .tag("action", "rotated").counter().count());
        assertEquals(1.0, registry.get(PictureMetrics.EXIF)
                .tag("action", "stripped").counter().count());
        assertEquals(1.0, registry.get(PictureMetrics.QUOTA_REJECTED)
                .tag("type", "storage").counter().count());
        assertEquals(1.0, registry.get(PictureMetrics.QUOTA_REJECTED)
                .tag("type", "count").counter().count());
        assertEquals(1.0, registry.get(PictureMetrics.UPLOAD_DUPLICATE).counter().count());
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
        // 与 Spring Boot 的 Redis CacheMetrics 保持同一组标签键
        assertTrue(scrape.contains("cache_manager=\"caffeine\""));
        assertTrue(scrape.contains("name=\"test.cache\""));
    }

    @Test
    @DisplayName("缓存指标：Caffeine 与 Spring Cache 的标签键一致时不会互相覆盖")
    void caffeineCache_tagsMatchSpringCache() {
        Cache<String, String> cache = Caffeine.newBuilder().recordStats().build();
        pictureMetrics.registerCaffeineCache("picture.list.local", cache);

        // Spring Boot 为 RedisCacheManager 注册的 cache.gets 带有这些标签；
        // 若 Caffeine 的标签键不一致，Prometheus registry 会拒绝第二个同名指标。
        Counter springCacheCounter = Counter.builder("cache.gets")
                .tag("cache", "tagCategory")
                .tag("cache.manager", "cacheManager")
                .tag("name", "tagCategory")
                .tag("result", "hit")
                .register(registry);
        springCacheCounter.increment();

        assertNotNull(registry.get("cache.gets")
                .tag("cache", "tagCategory")
                .tag("cache.manager", "cacheManager")
                .tag("name", "tagCategory")
                .tag("result", "hit")
                .counter());
    }
}

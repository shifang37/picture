package com.tzy.picturebackend.manager;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tzy.picturebackend.config.RagProperties;
import com.tzy.picturebackend.support.InMemoryRedis;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 问答缓存：key 维度隔离、内容往返、重建/换模型后失效。
 */
class RagAskCacheTest {

    private RagAskCache cache;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        Map<String, String> store = new ConcurrentHashMap<>();
        StringRedisTemplate redisTemplate = InMemoryRedis.createTemplate(store);
        cache = new RagAskCache(redisTemplate, objectMapper, new RagProperties());
    }

    @Test
    @DisplayName("key：问题、topK、索引版本、提示词版本、模型任一变化都不命中")
    void keyOf_isolatesAllDimensions() {
        String key = cache.keyOf("哪些图适合做海报", 8, "picture_vec_v2", "ask-v1", "deepseek-flash");

        assertTrue(key.startsWith("picture:rag:ask:cache:"));
        assertEquals(32, key.substring("picture:rag:ask:cache:".length()).length());
        assertNotEquals(key, cache.keyOf("哪些图适合做封面", 8, "picture_vec_v2", "ask-v1", "deepseek-flash"));
        assertNotEquals(key, cache.keyOf("哪些图适合做海报", 9, "picture_vec_v2", "ask-v1", "deepseek-flash"));
        assertNotEquals(key, cache.keyOf("哪些图适合做海报", 8, "picture_vec_v3", "ask-v1", "deepseek-flash"));
        assertNotEquals(key, cache.keyOf("哪些图适合做海报", 8, "picture_vec_v2", "ask-v2", "deepseek-flash"));
        assertNotEquals(key, cache.keyOf("哪些图适合做海报", 8, "picture_vec_v2", "ask-v1", "other-model"));
    }

    @Test
    @DisplayName("读写：回答与引用 id 可完整还原")
    void putAndGet_roundTrip() {
        RagAskCache.CachedAnswer answer = new RagAskCache.CachedAnswer(
                "有 2 张竖图比较合适", List.of(12L, 35L), false);

        cache.put("哪些图适合做竖版海报", 8, "picture_vec_v2", "ask-v1", "deepseek-flash", answer);
        RagAskCache.CachedAnswer hit = cache.get("哪些图适合做竖版海报", 8, "picture_vec_v2",
                "ask-v1", "deepseek-flash");

        assertNotNull(hit);
        assertEquals("有 2 张竖图比较合适", hit.answer());
        assertEquals(List.of(12L, 35L), hit.citedPictureIds());
        assertEquals(false, hit.refused());
    }

    @Test
    @DisplayName("未命中：返回 null，调用方回源")
    void get_returnsNullWhenMiss() {
        assertNull(cache.get("没有缓存", 8, "picture_vec_v2", "ask-v1", "deepseek-flash"));
    }
}

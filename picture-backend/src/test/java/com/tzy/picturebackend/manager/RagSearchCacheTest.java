package com.tzy.picturebackend.manager;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tzy.picturebackend.config.RagProperties;
import com.tzy.picturebackend.model.dto.picture.PictureQueryRequest;
import com.tzy.picturebackend.model.dto.rag.RagIdPage;
import com.tzy.picturebackend.support.InMemoryRedis;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 混合检索结果缓存：key 隔离、读写、命名空间失效与 Redis 降级。
 */
class RagSearchCacheTest {

    private Map<String, String> store;

    private StringRedisTemplate redisTemplate;

    private RagSearchCache cache;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        store = new ConcurrentHashMap<>();
        redisTemplate = InMemoryRedis.createTemplate(store);
        cache = new RagSearchCache(redisTemplate, objectMapper, new RagProperties());
    }

    @Test
    @DisplayName("key：带模式与命名空间，模式和分页不同都不会命中")
    void keyOf_isolatesModeAndPaging() {
        String key = cache.keyOf("hybrid", request(1, 10));

        assertTrue(key.startsWith("picture:rag:cache:hybrid:0:"), "实际 key: " + key);
        assertEquals(32, key.substring(key.lastIndexOf(':') + 1).length(), "摘要应为 32 位 md5");
        assertNotEquals(key, cache.keyOf("semantic", request(1, 10)));
        assertNotEquals(key, cache.keyOf("hybrid", request(2, 10)));
        assertNotEquals(key, cache.keyOf("hybrid", request(1, 20)));
    }

    @Test
    @DisplayName("key：标签顺序不同但内容相同应命中同一个 key")
    void keyOf_ignoresTagOrder() {
        PictureQueryRequest first = request(1, 10);
        first.setTags(List.of("标签B", "标签A"));
        PictureQueryRequest second = request(1, 10);
        second.setTags(List.of("标签A", "标签B"));

        assertEquals(cache.keyOf("hybrid", first), cache.keyOf("hybrid", second));
    }

    @Test
    @DisplayName("读写：写入 id 与分页信息后可以完整还原")
    void putAndGet_roundTrip() {
        PictureQueryRequest request = request(1, 10);
        cache.put("hybrid", request, idPage());

        RagIdPage hit = cache.get("hybrid", request);

        assertNotNull(hit);
        assertEquals(List.of(2L, 1L), hit.getIds());
        assertEquals(2, hit.getTotal());
        assertEquals(1, hit.getCurrent());
        assertEquals(10, hit.getSize());
    }

    @Test
    @DisplayName("失效：自增命名空间后旧结果不可达")
    void clear_switchesNamespace() {
        PictureQueryRequest request = request(1, 10);
        cache.put("hybrid", request, idPage());
        assertNotNull(cache.get("hybrid", request));

        cache.clear();

        assertNull(cache.get("hybrid", request));
        assertEquals("1", store.get("picture:rag:cache:ns"));
    }

    @Test
    @DisplayName("降级：Redis 不可用时读取返回 null，写入与清空不抛异常")
    void degradesWhenRedisIsDown() {
        StringRedisTemplate broken = mock(StringRedisTemplate.class);
        when(broken.opsForValue()).thenThrow(new RedisConnectionFailureException("connection refused"));
        RagSearchCache failing = new RagSearchCache(broken, objectMapper, new RagProperties());

        assertNull(failing.get("hybrid", request(1, 10)));
        assertDoesNotThrow(() -> failing.put("hybrid", request(1, 10), idPage()));
        assertDoesNotThrow(failing::clear);
    }

    private PictureQueryRequest request(int current, int pageSize) {
        PictureQueryRequest request = new PictureQueryRequest();
        request.setCurrent(current);
        request.setPageSize(pageSize);
        request.setSearchText("暖色调的日落");
        return request;
    }

    private RagIdPage idPage() {
        RagIdPage page = new RagIdPage();
        page.setIds(List.of(2L, 1L));
        page.setTotal(2);
        page.setCurrent(1);
        page.setSize(10);
        return page;
    }
}

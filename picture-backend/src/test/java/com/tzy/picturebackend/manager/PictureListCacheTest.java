package com.tzy.picturebackend.manager;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tzy.picturebackend.model.dto.picture.PictureListPage;
import com.tzy.picturebackend.model.dto.picture.PictureQueryRequest;
import com.tzy.picturebackend.model.vo.PictureVO;
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
 * 公开列表两级缓存测试（内存版 StringRedisTemplate）
 */
class PictureListCacheTest {

    private Map<String, String> store;

    private StringRedisTemplate redisTemplate;

    private PictureListCache cache;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        store = new ConcurrentHashMap<>();
        redisTemplate = InMemoryRedis.createTemplate(store);
        cache = new PictureListCache(redisTemplate, objectMapper);
    }

    @Test
    @DisplayName("key：带命名空间与查询摘要，分页不同则 key 不同")
    void keyOf_includesNamespaceAndQuery() {
        String key = cache.keyOf(request(1, 10));

        assertTrue(key.startsWith("picture:list:0:"), "实际 key: " + key);
        assertEquals(32, key.substring(key.lastIndexOf(':') + 1).length(), "摘要应为 32 位 md5");
        assertNotEquals(key, cache.keyOf(request(2, 10)));
        assertNotEquals(key, cache.keyOf(request(1, 20)));
    }

    @Test
    @DisplayName("key：标签顺序不同但内容相同应命中同一个 key")
    void keyOf_ignoresTagOrder() {
        PictureQueryRequest first = request(1, 10);
        first.setTags(List.of("标签B", "标签A"));
        PictureQueryRequest second = request(1, 10);
        second.setTags(List.of("标签A", "标签B"));

        assertEquals(cache.keyOf(first), cache.keyOf(second));
    }

    @Test
    @DisplayName("key：排序字段或方向不同必须区分，否则会命中错误的排序结果")
    void keyOf_includesSortFieldAndOrder() {
        PictureQueryRequest base = request(1, 10);
        base.setSortField("createTime");
        base.setSortOrder("descend");

        PictureQueryRequest otherField = request(1, 10);
        otherField.setSortField("name");
        otherField.setSortOrder("descend");

        PictureQueryRequest otherOrder = request(1, 10);
        otherOrder.setSortField("createTime");
        otherOrder.setSortOrder("ascend");

        assertNotEquals(cache.keyOf(base), cache.keyOf(otherField));
        assertNotEquals(cache.keyOf(base), cache.keyOf(otherOrder));
        assertNotEquals(cache.keyOf(otherField), cache.keyOf(otherOrder));
    }

    @Test
    @DisplayName("L1：写入后即使 Redis 数据损坏也能命中本地缓存")
    void get_hitsLocalCacheFirst() {
        PictureQueryRequest request = request(1, 10);
        cache.put(request, page());
        // 破坏 L2 的内容，若仍能返回说明命中的是 L1
        store.put(cache.keyOf(request), "not-json");

        PictureListPage hit = cache.get(request);

        assertNotNull(hit);
        assertEquals("缓存图片", hit.getRecords().get(0).getName());
    }

    @Test
    @DisplayName("L2：新的实例没有本地缓存时可以从 Redis 还原")
    void get_fallsBackToRedis() {
        PictureQueryRequest request = request(1, 10);
        cache.put(request, page());

        PictureListCache anotherInstance = new PictureListCache(redisTemplate, objectMapper);
        PictureListPage hit = anotherInstance.get(request);

        assertNotNull(hit);
        assertEquals(1, hit.getTotal());
        assertEquals("缓存图片", hit.getRecords().get(0).getName());
    }

    @Test
    @DisplayName("失效：自增命名空间后，同样的查询会换 key")
    void invalidate_switchesNamespace() {
        PictureQueryRequest request = request(1, 10);
        String before = cache.keyOf(request);

        cache.invalidate();

        assertNotEquals(before, cache.keyOf(request));
        assertEquals("1", store.get("picture:list:ns"));
    }

    @Test
    @DisplayName("降级：Redis 不可用时回源（返回 null），写入与失效不抛异常")
    void degradesWhenRedisIsDown() {
        PictureQueryRequest request = request(1, 10);
        StringRedisTemplate broken = mock(StringRedisTemplate.class);
        when(broken.opsForValue()).thenThrow(new RedisConnectionFailureException("connection refused"));
        PictureListCache failing = new PictureListCache(broken, objectMapper);

        assertNull(failing.get(request));
        assertDoesNotThrow(() -> failing.put(request, page()));
        assertNotNull(failing.get(request), "Redis 不可用时 L1 仍应可用");
        assertDoesNotThrow(failing::invalidate);
    }

    private PictureQueryRequest request(int current, int pageSize) {
        PictureQueryRequest request = new PictureQueryRequest();
        request.setCurrent(current);
        request.setPageSize(pageSize);
        request.setSortField("createTime");
        request.setSortOrder("descend");
        return request;
    }

    private PictureListPage page() {
        PictureVO picture = new PictureVO();
        picture.setId(1L);
        picture.setName("缓存图片");
        PictureListPage page = new PictureListPage();
        page.setRecords(List.of(picture));
        page.setTotal(1);
        page.setCurrent(1);
        page.setSize(10);
        return page;
    }
}

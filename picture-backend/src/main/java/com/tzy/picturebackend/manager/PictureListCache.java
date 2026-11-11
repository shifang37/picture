package com.tzy.picturebackend.manager;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.tzy.picturebackend.model.dto.picture.PictureListPage;
import com.tzy.picturebackend.model.dto.picture.PictureQueryRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 公开图片列表的两级缓存。
 * <p>
 * 只服务首页的 {@code POST /picture/list/page/vo}：先查 Caffeine（30 秒），再查 Redis（5 分钟 + 0~60 秒抖动），
 * 都未命中时由调用方回源数据库。缓存值只包含 records / total / current / size。
 * <p>
 * 失效方式：审核、编辑、删除、上传成功后对 {@code picture:list:ns} 执行 INCR，
 * 新请求用新命名空间拼 key，旧 key 自然过期。L1 最多比数据库滞后 30 秒。
 * Redis 异常时直接回源数据库，不影响列表可用性。
 */
@Component
@Slf4j
public class PictureListCache {

    private static final String KEY_PREFIX = "picture:list:";

    /**
     * 缓存命名空间计数器：自增一次，所有旧 key 立即不可达
     */
    private static final String NAMESPACE_KEY = "picture:list:ns";

    private static final Duration REDIS_TTL = Duration.ofMinutes(5);

    /**
     * 在基础 TTL 上再加 0~60 秒随机抖动，避免同一批 key 同时过期
     */
    private static final int REDIS_TTL_JITTER_SECONDS = 60;

    private static final Duration LOCAL_TTL = Duration.ofSeconds(30);

    private static final int LOCAL_MAX_SIZE = 500;

    private final StringRedisTemplate redisTemplate;

    private final ObjectMapper objectMapper;

    private final Cache<String, PictureListPage> localCache = Caffeine.newBuilder()
            .maximumSize(LOCAL_MAX_SIZE)
            .expireAfterWrite(LOCAL_TTL)
            .build();

    public PictureListCache(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 读取缓存：命中返回缓存页，未命中返回 null（调用方回源数据库）
     */
    public PictureListPage get(PictureQueryRequest request) {
        String key = keyOf(request);
        PictureListPage local = localCache.getIfPresent(key);
        if (local != null) {
            return local;
        }
        try {
            String json = redisTemplate.opsForValue().get(key);
            if (StrUtil.isNotBlank(json)) {
                PictureListPage page = objectMapper.readValue(json, PictureListPage.class);
                localCache.put(key, page);
                return page;
            }
        } catch (Exception e) {
            log.warn("读取图片列表缓存失败，回源数据库, key = {}", key, e);
        }
        return null;
    }

    /**
     * 写入缓存：先写 L1，再写 L2；Redis 异常只记日志，不影响本次请求
     */
    public void put(PictureQueryRequest request, PictureListPage page) {
        String key = keyOf(request);
        localCache.put(key, page);
        try {
            String json = objectMapper.writeValueAsString(page);
            Duration ttl = REDIS_TTL.plusSeconds(
                    ThreadLocalRandom.current().nextInt(REDIS_TTL_JITTER_SECONDS + 1));
            redisTemplate.opsForValue().set(key, json, ttl);
        } catch (Exception e) {
            log.warn("写入图片列表缓存失败, key = {}", key, e);
        }
    }

    /**
     * 图片可见内容变化后调用：切换命名空间，让所有旧 key 失效
     */
    public void invalidate() {
        try {
            redisTemplate.opsForValue().increment(NAMESPACE_KEY);
        } catch (RuntimeException e) {
            log.warn("图片列表缓存命名空间自增失败", e);
        }
    }

    /**
     * 缓存 key：{@code picture:list:{ns}:{md5(规范化查询 JSON)}}
     */
    String keyOf(PictureQueryRequest request) {
        String namespace = currentNamespace();
        byte[] canonical = canonicalJson(request).getBytes(StandardCharsets.UTF_8);
        return KEY_PREFIX + namespace + ":" + DigestUtils.md5DigestAsHex(canonical);
    }

    private String currentNamespace() {
        try {
            String value = redisTemplate.opsForValue().get(NAMESPACE_KEY);
            return StrUtil.isBlank(value) ? "0" : value;
        } catch (RuntimeException e) {
            log.warn("读取图片列表缓存命名空间失败，按 0 处理", e);
            return "0";
        }
    }

    /**
     * 把查询条件规范化成固定顺序的 JSON：标签排序后参与计算，避免顺序不同导致缓存不命中
     */
    private String canonicalJson(PictureQueryRequest request) {
        Map<String, Object> fields = new TreeMap<>();
        fields.put("id", request.getId());
        fields.put("name", request.getName());
        fields.put("introduction", request.getIntroduction());
        fields.put("category", request.getCategory());
        List<String> tags = request.getTags();
        fields.put("tags", tags == null ? null : tags.stream().sorted().toList());
        fields.put("picSize", request.getPicSize());
        fields.put("picWidth", request.getPicWidth());
        fields.put("picHeight", request.getPicHeight());
        fields.put("picScale", request.getPicScale());
        fields.put("picFormat", request.getPicFormat());
        fields.put("searchText", request.getSearchText());
        fields.put("userId", request.getUserId());
        fields.put("reviewStatus", request.getReviewStatus());
        fields.put("current", request.getCurrent());
        fields.put("pageSize", request.getPageSize());
        try {
            return objectMapper.writeValueAsString(fields);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("规范化图片列表查询条件失败", e);
        }
    }
}

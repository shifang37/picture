package com.tzy.picturebackend.manager;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tzy.picturebackend.config.RagProperties;
import com.tzy.picturebackend.model.dto.picture.PictureQueryRequest;
import com.tzy.picturebackend.model.dto.rag.RagIdPage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 混合 / 语义检索结果缓存。
 * <p>
 * 为什么不能复用 {@link PictureListCache}：它的 key 没有检索模式维度，命中率统计也按
 * 「公开列表」口径，混用会把 hybrid 的结果当成 keyword 返回。这里单独用
 * {@code picture:rag:cache:<mode>:<ns>:<摘要>}，TTL 默认 5 分钟，重建索引后自增命名空间立即失效。
 * <p>
 * 只缓存 id 与分页信息，不缓存 VO：作者资料与审核可见性依请求者而变化，缓存整页 VO 会串数据。
 * Redis 异常时直接回源（返回 null），不影响检索可用性。
 */
@Component
@Slf4j
public class RagSearchCache {

    private static final String KEY_PREFIX = "picture:rag:cache:";

    /**
     * 缓存命名空间计数器：自增一次，所有旧 key 立即不可达
     */
    private static final String NAMESPACE_KEY = "picture:rag:cache:ns";

    private static final int TTL_JITTER_SECONDS = 30;

    private final StringRedisTemplate redisTemplate;

    private final ObjectMapper objectMapper;

    private final RagProperties ragProperties;

    public RagSearchCache(StringRedisTemplate redisTemplate, ObjectMapper objectMapper,
                          RagProperties ragProperties) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.ragProperties = ragProperties;
    }

    /**
     * 读取缓存：命中返回 id 页，未命中返回 null（调用方回源检索）
     */
    public RagIdPage get(String mode, PictureQueryRequest request) {
        String key = keyOf(mode, request);
        try {
            String json = redisTemplate.opsForValue().get(key);
            if (StrUtil.isBlank(json)) {
                return null;
            }
            return objectMapper.readValue(json, RagIdPage.class);
        } catch (Exception e) {
            log.warn("读取 RAG 检索缓存失败，回源检索, key = {}", key, e);
            return null;
        }
    }

    /**
     * 写入缓存；只缓存成功结果，降级结果由调用方跳过
     */
    public void put(String mode, PictureQueryRequest request, RagIdPage page) {
        String key = keyOf(mode, request);
        try {
            String json = objectMapper.writeValueAsString(page);
            redisTemplate.opsForValue().set(key, json, ttl());
        } catch (Exception e) {
            log.warn("写入 RAG 检索缓存失败, key = {}", key, e);
        }
    }

    /**
     * 重建索引后调用：切换命名空间，让所有旧结果立即失效
     */
    public void clear() {
        try {
            redisTemplate.opsForValue().increment(NAMESPACE_KEY);
        } catch (RuntimeException e) {
            log.warn("RAG 检索缓存命名空间自增失败", e);
        }
    }

    /**
     * 缓存 key：{@code picture:rag:cache:<mode>:<ns>:<md5(规范化查询 JSON)>}
     */
    String keyOf(String mode, PictureQueryRequest request) {
        String namespace = currentNamespace();
        byte[] canonical = canonicalJson(request).getBytes(StandardCharsets.UTF_8);
        return KEY_PREFIX + mode + ":" + namespace + ":" + DigestUtils.md5DigestAsHex(canonical);
    }

    private Duration ttl() {
        int seconds = Math.max(1, ragProperties.getSearch().getResultCacheSeconds());
        return Duration.ofSeconds(seconds + (long) (Math.random() * (TTL_JITTER_SECONDS + 1)));
    }

    private String currentNamespace() {
        try {
            String value = redisTemplate.opsForValue().get(NAMESPACE_KEY);
            return StrUtil.isBlank(value) ? "0" : value;
        } catch (RuntimeException e) {
            log.warn("读取 RAG 检索缓存命名空间失败，按 0 处理", e);
            return "0";
        }
    }

    /**
     * 把影响召回结果的字段规范化成固定顺序的 JSON：标签排序后参与摘要，避免顺序不同导致不命中
     */
    private String canonicalJson(PictureQueryRequest request) {
        Map<String, Object> fields = new TreeMap<>();
        fields.put("searchText", StrUtil.trimToNull(request.getSearchText()));
        fields.put("category", request.getCategory());
        List<String> tags = request.getTags();
        fields.put("tags", tags == null ? null : tags.stream().filter(StrUtil::isNotBlank).sorted().toList());
        fields.put("userId", request.getUserId());
        fields.put("current", request.getCurrent());
        fields.put("pageSize", request.getPageSize());
        try {
            return objectMapper.writeValueAsString(fields);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("规范化 RAG 检索条件失败", e);
        }
    }
}

package com.tzy.picturebackend.manager;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tzy.picturebackend.config.RagProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 图库问答回答缓存。
 * <p>
 * 只缓存「模型产出 + 引用 id」，不缓存整段 VO：图片名称、URL 与可见性都可能变化，
 * 命中后仍要回表重新构造引用，任何一张图不再公开就当作未命中重新生成，
 * 保证缓存不会让已删除 / 已下架的图片继续出现在回答里。
 * <p>
 * key 含索引名（重建后别名指向新索引就会自然失效）、提示词版本与模型：
 * 换模型或改提示词后旧答案立即不可达。Redis 异常时直接回源，不影响问答可用性。
 */
@Slf4j
public class RagAskCache {

    private static final String KEY_PREFIX = "picture:rag:ask:cache:";

    private static final int TTL_JITTER_SECONDS = 60;

    private final StringRedisTemplate redisTemplate;

    private final ObjectMapper objectMapper;

    private final RagProperties ragProperties;

    public RagAskCache(StringRedisTemplate redisTemplate, ObjectMapper objectMapper,
                       RagProperties ragProperties) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.ragProperties = ragProperties;
    }

    /**
     * 读取缓存；未命中、Redis 异常或内容损坏都返回 null（调用方回源）
     */
    public CachedAnswer get(String question, int topK, String indexVersion,
                            String promptVersion, String model) {
        String key = keyOf(question, topK, indexVersion, promptVersion, model);
        try {
            String json = redisTemplate.opsForValue().get(key);
            if (StrUtil.isBlank(json)) {
                return null;
            }
            return objectMapper.readValue(json, CachedAnswer.class);
        } catch (Exception e) {
            log.warn("读取图库问答缓存失败，回源生成, key = {}", key, e);
            return null;
        }
    }

    /**
     * 写入缓存；只写已通过校验的结果，降级结果不缓存（下一次请求应重新尝试）
     */
    public void put(String question, int topK, String indexVersion,
                    String promptVersion, String model, CachedAnswer answer) {
        if (answer == null) {
            return;
        }
        String key = keyOf(question, topK, indexVersion, promptVersion, model);
        try {
            String json = objectMapper.writeValueAsString(answer);
            redisTemplate.opsForValue().set(key, json, ttl());
        } catch (Exception e) {
            log.warn("写入图库问答缓存失败, key = {}", key, e);
        }
    }

    /**
     * 缓存 key：{@code picture:rag:ask:cache:<md5(规范化字段)>}
     */
    String keyOf(String question, int topK, String indexVersion, String promptVersion, String model) {
        Map<String, Object> fields = new TreeMap<>();
        fields.put("question", StrUtil.trimToEmpty(question));
        fields.put("topK", topK);
        fields.put("index", StrUtil.nullToEmpty(indexVersion));
        fields.put("prompt", StrUtil.nullToEmpty(promptVersion));
        fields.put("model", StrUtil.nullToEmpty(model));
        try {
            String canonical = objectMapper.writeValueAsString(fields);
            return KEY_PREFIX + DigestUtils.md5DigestAsHex(canonical.getBytes(StandardCharsets.UTF_8));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("构造图库问答缓存 key 失败", e);
        }
    }

    private Duration ttl() {
        int seconds = Math.max(1, ragProperties.getAsk().getCacheSeconds());
        return Duration.ofSeconds(seconds + (long) (Math.random() * (TTL_JITTER_SECONDS + 1)));
    }

    /**
     * 缓存内容。引用顺序即模型引用顺序；{@code refused=true} 时引用为空。
     */
    public record CachedAnswer(String answer, List<Long> citedPictureIds, boolean refused) {

        public CachedAnswer {
            citedPictureIds = citedPictureIds == null ? List.of() : List.copyOf(citedPictureIds);
        }
    }
}

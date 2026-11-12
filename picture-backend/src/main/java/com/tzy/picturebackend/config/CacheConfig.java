package com.tzy.picturebackend.config;

import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;
import java.util.Map;

/**
 * 缓存配置：标签/分类字典用 Spring Cache 存 Redis。
 * <p>
 * 用 Redis 而不是本地缓存，是为了管理员修改字典后失效对所有实例同时生效。
 * 公开图片列表是两级缓存（Caffeine + Redis），由 {@code PictureListCache} 手工管理，不走这里。
 */
@Configuration
@EnableCaching
public class CacheConfig {

    /**
     * 标签/分类字典缓存名
     */
    public static final String TAG_CATEGORY_CACHE = "tagCategory";

    /**
     * 管理端数据看板缓存名
     */
    public static final String ANALYZE_CACHE = "analyze";

    /**
     * 作者主页公开统计缓存名
     */
    public static final String USER_PROFILE_CACHE = "userProfile";

    /**
     * 搜索联想 / 热词缓存名
     */
    public static final String SEARCH_META_CACHE = "searchMeta";

    private static final Duration TAG_CATEGORY_TTL = Duration.ofMinutes(30);

    private static final Duration ANALYZE_TTL = Duration.ofMinutes(5);

    /**
     * 作者主页统计 TTL：上传、过审、删除、下载、收藏都会改变统计值，
     * 5 分钟延迟可接受；作者本人查看自己的准确数据请走「我的图片」。
     */
    private static final Duration USER_PROFILE_TTL = Duration.ofMinutes(5);

    /**
     * 搜索联想 / 热词 TTL：前缀匹配与标签聚合本身不重，但联想是「边输入边请求」的接口，
     * 10 分钟缓存既能把 QPS 压到接近 0，又不会让新过审的图片长时间搜不到。
     */
    private static final Duration SEARCH_META_TTL = Duration.ofMinutes(10);

    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        RedisCacheConfiguration defaults = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(TAG_CATEGORY_TTL)
                .disableCachingNullValues()
                .serializeKeysWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new StringRedisSerializer()))
                // 存 JSON 而不是 JDK 序列化：值里不带类版本耦合，便于排查
                .serializeValuesWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new GenericJackson2JsonRedisSerializer()));
        RedisCacheConfiguration analyzeConfig = defaults.entryTtl(ANALYZE_TTL);
        RedisCacheConfiguration userProfileConfig = defaults.entryTtl(USER_PROFILE_TTL);
        RedisCacheConfiguration searchMetaConfig = defaults.entryTtl(SEARCH_META_TTL);
        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(defaults)
                // 打开 RedisCache 的命中/未命中统计：否则 cache_gets_total 等指标恒为 0
                .enableStatistics()
                .withInitialCacheConfigurations(Map.of(
                        TAG_CATEGORY_CACHE, defaults,
                        ANALYZE_CACHE, analyzeConfig,
                        USER_PROFILE_CACHE, userProfileConfig,
                        SEARCH_META_CACHE, searchMetaConfig))
                .build();
    }
}

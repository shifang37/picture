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

    private static final Duration TAG_CATEGORY_TTL = Duration.ofMinutes(30);

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
        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(defaults)
                .withInitialCacheConfigurations(Map.of(TAG_CATEGORY_CACHE, defaults))
                .build();
    }
}

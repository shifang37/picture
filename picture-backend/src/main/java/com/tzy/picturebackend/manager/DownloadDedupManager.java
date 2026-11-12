package com.tzy.picturebackend.manager;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 下载计数去重：同一「用户 / IP」对同一张图片在同一个自然日内只计一次。
 * <p>
 * 使用 Redis {@code SET NX EX} 原子占位：只有第一次成功占位的请求才允许自增下载次数，
 * 后续重复点击、脚本刷量都会被忽略。key 中带日期，TTL 到当天 24:00，
 * 跨天后自然失效，不需要额外清理。
 * <p>
 * Redis 故障时 {@link #markIfFirst(String, Long)} 返回 {@code false}：下载文件本身仍然可用，
 * 但本次不增加计数。宁可少计也不能让刷量在故障期间穿透。
 */
@Component
@Slf4j
public class DownloadDedupManager {

    static final String KEY_PREFIX = "picture:download:dedup:";

    /**
     * 按国内业务日划分「天」，与数据库/前端展示时区保持一致
     */
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private static final DateTimeFormatter DAY_FORMAT = DateTimeFormatter.BASIC_ISO_DATE;

    private final StringRedisTemplate redisTemplate;

    public DownloadDedupManager(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 占位：返回 true 表示该身份今天第一次下载这张图片，可以计数。
     *
     * @param identity  下载者标识，形如 {@code user:1} 或 {@code ip:10.0.0.1}
     * @param pictureId 图片 id
     * @return 是否为当天首次下载
     */
    public boolean markIfFirst(String identity, Long pictureId) {
        if (StrUtil.isBlank(identity) || pictureId == null || pictureId <= 0) {
            return false;
        }
        ZonedDateTime now = ZonedDateTime.now(ZONE);
        String key = KEY_PREFIX + now.toLocalDate().format(DAY_FORMAT) + ":" + pictureId + ":" + identity;
        Duration ttl = ttlUntilTomorrow(now);
        try {
            Boolean first = redisTemplate.opsForValue().setIfAbsent(key, "1", ttl);
            return Boolean.TRUE.equals(first);
        } catch (RuntimeException e) {
            log.error("下载去重占位失败，本次不增加下载次数, key = {}", key, e);
            return false;
        }
    }

    /**
     * 距离次日 00:00 的剩余时间；至少保留 1 分钟，避免极端边界下 TTL 为 0。
     */
    private Duration ttlUntilTomorrow(ZonedDateTime now) {
        Duration ttl = Duration.between(now, now.toLocalDate().plusDays(1).atStartOfDay(ZONE));
        return ttl.isNegative() || ttl.isZero() ? Duration.ofMinutes(1) : ttl;
    }
}

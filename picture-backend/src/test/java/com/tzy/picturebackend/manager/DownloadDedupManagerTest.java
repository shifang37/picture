package com.tzy.picturebackend.manager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 下载去重测试：首次占位成功才计数，重复点击与 Redis 故障都不计数
 */
class DownloadDedupManagerTest {

    private StringRedisTemplate redisTemplate;

    private ValueOperations<String, String> valueOperations;

    private DownloadDedupManager downloadDedupManager;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        downloadDedupManager = new DownloadDedupManager(redisTemplate);
    }

    @Test
    @DisplayName("去重：当天首次下载占位成功，key 包含日期、图片和用户标识")
    void markIfFirst_returnsTrueForFirstDownload() {
        when(valueOperations.setIfAbsent(anyString(), eq("1"), any(Duration.class))).thenReturn(true);

        assertTrue(downloadDedupManager.markIfFirst("user:100", 7L));

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.forClass(Duration.class);
        verify(valueOperations).setIfAbsent(keyCaptor.capture(), eq("1"), ttlCaptor.capture());
        String today = ZonedDateTime.now(java.time.ZoneId.of("Asia/Shanghai"))
                .toLocalDate()
                .format(DateTimeFormatter.BASIC_ISO_DATE);
        String key = keyCaptor.getValue();
        assertTrue(key.startsWith(DownloadDedupManager.KEY_PREFIX + today));
        assertTrue(key.endsWith(":7:user:100"));
        assertNotNull(ttlCaptor.getValue());
        assertTrue(ttlCaptor.getValue().toSeconds() > 0);
        assertTrue(ttlCaptor.getValue().toDays() <= 1);
    }

    @Test
    @DisplayName("去重：同一天重复下载占位失败")
    void markIfFirst_returnsFalseForDuplicate() {
        when(valueOperations.setIfAbsent(anyString(), eq("1"), any(Duration.class))).thenReturn(false);

        assertFalse(downloadDedupManager.markIfFirst("ip:10.0.0.1", 7L));
    }

    @Test
    @DisplayName("降级：Redis 不可用时返回 false，本次不计数")
    void markIfFirst_returnsFalseWhenRedisFails() {
        when(valueOperations.setIfAbsent(anyString(), eq("1"), any(Duration.class)))
                .thenThrow(new RedisConnectionFailureException("connection refused"));

        assertFalse(downloadDedupManager.markIfFirst("ip:10.0.0.1", 7L));
    }

    @Test
    @DisplayName("参数：空身份或非法图片 id 不访问 Redis")
    void markIfFirst_skipsInvalidArguments() {
        assertFalse(downloadDedupManager.markIfFirst("", 7L));
        assertFalse(downloadDedupManager.markIfFirst("user:1", null));
        assertFalse(downloadDedupManager.markIfFirst("user:1", 0L));
        verifyNoInteractions(valueOperations);
    }
}

package com.tzy.picturebackend.service.impl;

import com.tzy.picturebackend.config.RagProperties;
import com.tzy.picturebackend.event.NotificationEventPublisher;
import com.tzy.picturebackend.metrics.PictureMetrics;
import com.tzy.picturebackend.model.dto.notification.NotificationMessage;
import com.tzy.picturebackend.model.dto.rag.RagDuplicateResult;
import com.tzy.picturebackend.model.dto.rag.RagSimilarMatch;
import com.tzy.picturebackend.model.entity.Picture;
import com.tzy.picturebackend.model.enums.NotificationTypeEnum;
import com.tzy.picturebackend.service.PictureService;
import com.tzy.picturebackend.service.RagSearchService;
import com.tzy.picturebackend.support.InMemoryRedis;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 近重复检测：双阈值、通知内容与 Redis / 通知表双层去重、失败降级。
 */
class RagDuplicateServiceImplTest {

    private RagSearchService ragSearchService;

    private PictureService pictureService;

    private NotificationEventPublisher notificationEventPublisher;

    private Map<String, String> store;

    private StringRedisTemplate redisTemplate;

    private RagProperties ragProperties;

    private SimpleMeterRegistry meterRegistry;

    private RagDuplicateServiceImpl service;

    private Picture picture;

    @BeforeEach
    void setUp() {
        ragSearchService = mock(RagSearchService.class);
        pictureService = mock(PictureService.class);
        notificationEventPublisher = mock(NotificationEventPublisher.class);
        store = new ConcurrentHashMap<>();
        redisTemplate = InMemoryRedis.createTemplate(store);
        ragProperties = new RagProperties();
        meterRegistry = new SimpleMeterRegistry();
        service = new RagDuplicateServiceImpl(ragSearchService, pictureService,
                notificationEventPublisher, redisTemplate, ragProperties,
                new PictureMetrics(meterRegistry));
        when(ragSearchService.enabled()).thenReturn(true);

        picture = new Picture();
        picture.setId(1L);
        picture.setUserId(100L);
        picture.setName("新上传的日落");
    }

    @Test
    @DisplayName("没有候选：MISS，不查库不发通知")
    void check_missWhenNoCandidate() {
        when(ragSearchService.nearestSameUser(1L, 100L)).thenReturn(Optional.empty());

        RagDuplicateResult result = service.check(picture);

        assertEquals(RagDuplicateResult.Status.MISS, result.status());
        assertFalse(result.notified());
        assertEquals(1.0, metric("miss"));
        verify(pictureService, never()).getById(any());
        verify(notificationEventPublisher, never()).publish(any());
    }

    @Test
    @DisplayName("低于疑似阈值：MISS")
    void check_missBelowSuspectThreshold() {
        when(ragSearchService.nearestSameUser(1L, 100L))
                .thenReturn(Optional.of(new RagSimilarMatch(9L, 0.5)));

        RagDuplicateResult result = service.check(picture);

        assertEquals(RagDuplicateResult.Status.MISS, result.status());
        assertEquals(1.0, metric("miss"));
        verify(notificationEventPublisher, never()).publish(any());
    }

    @Test
    @DisplayName("双阈值：0.98 只记 suspect，不打扰作者")
    void check_suspectBetweenThresholds() {
        when(ragSearchService.nearestSameUser(1L, 100L))
                .thenReturn(Optional.of(new RagSimilarMatch(9L, 0.98)));

        RagDuplicateResult result = service.check(picture);

        assertEquals(RagDuplicateResult.Status.SUSPECT, result.status());
        assertFalse(result.notified());
        assertEquals(0.98, result.cosine(), 1e-9);
        assertEquals(1.0, metric("suspect"));
        verify(notificationEventPublisher, never()).publish(any());
        verify(pictureService, never()).getById(any());
    }

    @Test
    @DisplayName("超过判重阈值：发通知，内容含两张图名称，写入 30 天去重键")
    void check_hitNotifiesOnce() {
        when(ragSearchService.nearestSameUser(1L, 100L))
                .thenReturn(Optional.of(new RagSimilarMatch(9L, 0.99)));
        Picture similar = picture(9L, "已发布的日落");
        when(pictureService.getById(9L)).thenReturn(similar);

        RagDuplicateResult result = service.check(picture);

        assertEquals(RagDuplicateResult.Status.HIT, result.status());
        assertTrue(result.notified());
        assertEquals(1.0, metric("hit"));
        ArgumentCaptor<NotificationMessage> captor = ArgumentCaptor.forClass(NotificationMessage.class);
        verify(notificationEventPublisher).publish(captor.capture());
        NotificationMessage message = captor.getValue();
        assertEquals(NotificationTypeEnum.RAG_DUPLICATE, message.getType());
        assertEquals(100L, message.getUserId());
        assertEquals(1L, message.getBizId());
        assertEquals("RAG_DUPLICATE:1:9", message.getDedupeKey(), "同一对图片的键必须与顺序无关");
        assertTrue(message.getContent().contains("新上传的日落"));
        assertTrue(message.getContent().contains("已发布的日落"));
        assertTrue(store.containsKey("picture:rag:dup:1:9"));
    }

    @Test
    @DisplayName("同一对图片已通知：Redis 去重键生效，不再发第二条")
    void check_doesNotNotifyAgain() {
        store.put("picture:rag:dup:1:9", "1");
        when(ragSearchService.nearestSameUser(1L, 100L))
                .thenReturn(Optional.of(new RagSimilarMatch(9L, 0.99)));
        when(pictureService.getById(9L)).thenReturn(picture(9L, "已发布的日落"));

        RagDuplicateResult result = service.check(picture);

        assertEquals(RagDuplicateResult.Status.HIT, result.status());
        assertFalse(result.notified());
        assertEquals(1.0, metric("hit"), "命中检测仍应计数，只是不再重复提示");
        verify(notificationEventPublisher, never()).publish(any());
    }

    @Test
    @DisplayName("候选图片已删除：不通知，但检测仍记为命中")
    void check_skipsMissingCandidate() {
        when(ragSearchService.nearestSameUser(1L, 100L))
                .thenReturn(Optional.of(new RagSimilarMatch(9L, 0.99)));
        when(pictureService.getById(9L)).thenReturn(null);

        RagDuplicateResult result = service.check(picture);

        assertEquals(RagDuplicateResult.Status.HIT, result.status());
        assertFalse(result.notified());
        verify(notificationEventPublisher, never()).publish(any());
    }

    @Test
    @DisplayName("向量查询失败：返回 ERROR，不抛出，图片索引不受影响")
    void check_returnsErrorOnSearchFailure() {
        when(ragSearchService.nearestSameUser(1L, 100L))
                .thenThrow(new IllegalStateException("es down"));

        RagDuplicateResult result = service.check(picture);

        assertEquals(RagDuplicateResult.Status.ERROR, result.status());
        assertEquals(1.0, metric("error"));
        verify(notificationEventPublisher, never()).publish(any());
    }

    @Test
    @DisplayName("Redis 不可用：仍发通知，由通知表唯一键兜底")
    void check_notifiesWhenRedisFails() {
        StringRedisTemplate broken = mock(StringRedisTemplate.class);
        when(broken.opsForValue()).thenThrow(new IllegalStateException("redis down"));
        service = new RagDuplicateServiceImpl(ragSearchService, pictureService,
                notificationEventPublisher, broken, ragProperties,
                new PictureMetrics(meterRegistry));
        when(ragSearchService.nearestSameUser(1L, 100L))
                .thenReturn(Optional.of(new RagSimilarMatch(9L, 0.99)));
        when(pictureService.getById(9L)).thenReturn(picture(9L, "已发布的日落"));

        RagDuplicateResult result = service.check(picture);

        assertTrue(result.notified());
        verify(notificationEventPublisher).publish(any(NotificationMessage.class));
    }

    @Test
    @DisplayName("缺少 id / 作者：直接 MISS，不查向量")
    void check_requiresIdentity() {
        Picture incomplete = new Picture();

        assertEquals(RagDuplicateResult.Status.MISS, service.check(incomplete).status());
        verify(ragSearchService, never()).nearestSameUser(any(), any());
    }

    private Picture picture(Long id, String name) {
        Picture item = new Picture();
        item.setId(id);
        item.setName(name);
        item.setUserId(100L);
        return item;
    }

    private double metric(String result) {
        Counter counter = meterRegistry.find(PictureMetrics.RAG_DUPLICATE).tag("result", result).counter();
        return counter == null ? 0.0 : counter.count();
    }
}

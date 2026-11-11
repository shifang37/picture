package com.tzy.picturebackend.mq;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.tzy.picturebackend.config.MachineReviewProperties;
import com.tzy.picturebackend.event.PictureEventPublisher;
import com.tzy.picturebackend.model.entity.Picture;
import com.tzy.picturebackend.service.PictureService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 机审兜底任务：开关、Redis 互斥、扫描条件与重新发布
 */
class MachineReviewFallbackTaskTest {

    private PictureService pictureService;

    private PictureEventPublisher pictureEventPublisher;

    private StringRedisTemplate stringRedisTemplate;

    private ValueOperations<String, String> valueOperations;

    private MachineReviewProperties machineReviewProperties;

    private MachineReviewFallbackTask fallbackTask;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        pictureService = mock(PictureService.class);
        pictureEventPublisher = mock(PictureEventPublisher.class);
        stringRedisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        machineReviewProperties = new MachineReviewProperties();
        machineReviewProperties.setEnabled(true);
        fallbackTask = new MachineReviewFallbackTask(pictureService, pictureEventPublisher,
                stringRedisTemplate, machineReviewProperties);
    }

    @Test
    @DisplayName("开关关闭：不抢锁、不查库")
    void republish_disabledSkips() {
        machineReviewProperties.setEnabled(false);

        fallbackTask.republishMissedUploads();

        verifyNoInteractions(stringRedisTemplate, pictureService, pictureEventPublisher);
    }

    @Test
    @DisplayName("多实例互斥：没抢到锁的实例不扫描")
    void republish_skipsWhenLockNotAcquired() {
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(false);

        fallbackTask.republishMissedUploads();

        verifyNoInteractions(pictureService, pictureEventPublisher);
    }

    @Test
    @DisplayName("没有遗漏图片时不发布事件")
    void republish_handlesEmptyResult() {
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(true);
        when(pictureService.list(any(Wrapper.class))).thenReturn(List.of());

        fallbackTask.republishMissedUploads();

        verifyNoInteractions(pictureEventPublisher);
    }

    @Test
    @DisplayName("扫描条件：待审核、机审字段为空、超过 5 分钟，按创建时间升序且最多 100 条")
    void republish_scansStalePendingPictures() {
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(true);
        Picture first = picture(1L);
        Picture second = picture(2L);
        when(pictureService.list(any(Wrapper.class))).thenReturn(List.of(first, second));

        fallbackTask.republishMissedUploads();

        ArgumentCaptor<Wrapper<Picture>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(pictureService).list(captor.capture());
        String sql = captor.getValue().getSqlSegment();
        assertTrue(sql.contains("reviewStatus"), "实际 SQL: " + sql);
        assertTrue(sql.contains("machineReviewStatus IS NULL"), "实际 SQL: " + sql);
        assertTrue(sql.contains("createTime"), "实际 SQL: " + sql);
        assertTrue(sql.contains("ORDER BY createTime ASC"), "实际 SQL: " + sql);
        assertTrue(sql.contains("LIMIT 100"), "实际 SQL: " + sql);
        verify(pictureEventPublisher).pictureUploaded(1L);
        verify(pictureEventPublisher).pictureUploaded(2L);
    }

    @Test
    @DisplayName("查询失败：只记录日志，不中断调度")
    void republish_swallowsQueryFailure() {
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(true);
        when(pictureService.list(any(Wrapper.class))).thenThrow(new RuntimeException("db down"));

        assertDoesNotThrow(() -> fallbackTask.republishMissedUploads());
        verify(pictureEventPublisher, never()).pictureUploaded(any());
    }

    @Test
    @DisplayName("Redis 异常：放弃本轮，避免多实例同时扫描")
    void republish_skipsWhenRedisFails() {
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenThrow(new RuntimeException("redis down"));

        assertDoesNotThrow(() -> fallbackTask.republishMissedUploads());
        verifyNoInteractions(pictureService, pictureEventPublisher);
    }

    private Picture picture(Long id) {
        Picture picture = new Picture();
        picture.setId(id);
        return picture;
    }
}

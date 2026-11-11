package com.tzy.picturebackend.mq;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.tzy.picturebackend.model.entity.Picture;
import com.tzy.picturebackend.service.PictureSearchService;
import com.tzy.picturebackend.service.PictureService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 搜索索引一致性巡检：数量不一致只告警，不抛异常
 */
class PictureSearchConsistencyTaskTest {

    private PictureService pictureService;

    private PictureSearchService pictureSearchService;

    private PictureSearchConsistencyTask task;

    @BeforeEach
    void setUp() {
        pictureService = mock(PictureService.class);
        pictureSearchService = mock(PictureSearchService.class);
        task = new PictureSearchConsistencyTask(pictureService, pictureSearchService);
    }

    @Test
    @DisplayName("巡检：数量一致时正常结束")
    void checkConsistency_matchingCounts() {
        when(pictureService.count(any(Wrapper.class))).thenReturn(5L);
        when(pictureSearchService.countIndexed()).thenReturn(5L);

        assertDoesNotThrow(task::checkConsistency);

        verify(pictureService).count(any(Wrapper.class));
        verify(pictureSearchService).countIndexed();
    }

    @Test
    @DisplayName("巡检：数量不一致只记录告警，不抛异常")
    void checkConsistency_mismatchedCountsDoesNotThrow() {
        when(pictureService.count(any(Wrapper.class))).thenReturn(5L);
        when(pictureSearchService.countIndexed()).thenReturn(4L);

        assertDoesNotThrow(task::checkConsistency);
    }

    @Test
    @DisplayName("巡检：数据库读取失败时跳过索引统计")
    void checkConsistency_databaseFailureSkipsIndex() {
        when(pictureService.count(any(Wrapper.class))).thenThrow(new RuntimeException("db down"));

        assertDoesNotThrow(task::checkConsistency);

        verifyNoInteractions(pictureSearchService);
    }
}

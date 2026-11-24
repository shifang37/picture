package com.tzy.picturebackend.mq;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.tzy.picturebackend.service.PictureSearchService;
import com.tzy.picturebackend.service.PictureService;
import com.tzy.picturebackend.service.RagEmbeddingStateService;
import com.tzy.picturebackend.service.RagIndexService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 索引一致性巡检：数量不一致只告警，不抛异常；RAG 未开启时不查向量状态。
 */
class PictureSearchConsistencyTaskTest {

    private PictureService pictureService;

    private PictureSearchService pictureSearchService;

    private RagEmbeddingStateService ragEmbeddingStateService;

    private RagIndexService ragIndexService;

    private ObjectProvider<RagIndexService> ragIndexServiceProvider;

    private PictureSearchConsistencyTask task;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        pictureService = mock(PictureService.class);
        pictureSearchService = mock(PictureSearchService.class);
        ragEmbeddingStateService = mock(RagEmbeddingStateService.class);
        ragIndexService = mock(RagIndexService.class);
        ragIndexServiceProvider = mock(ObjectProvider.class);
        when(ragIndexServiceProvider.getIfAvailable()).thenReturn(null);
        task = new PictureSearchConsistencyTask(pictureService, pictureSearchService,
                ragEmbeddingStateService, ragIndexServiceProvider);
    }

    @Test
    @DisplayName("巡检：数量一致时正常结束")
    void checkConsistency_matchingCounts() {
        when(pictureService.count(any(Wrapper.class))).thenReturn(5L);
        when(pictureSearchService.countIndexed()).thenReturn(5L);

        assertDoesNotThrow(task::checkConsistency);

        verify(pictureService).count(any(Wrapper.class));
        verify(pictureSearchService).countIndexed();
        verifyNoInteractions(ragEmbeddingStateService);
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

    @Test
    @DisplayName("向量巡检：RAG 可用且数量一致时正常结束")
    @SuppressWarnings("unchecked")
    void checkConsistency_ragCountsMatch() {
        when(pictureService.count(any(Wrapper.class))).thenReturn(5L);
        when(pictureSearchService.countIndexed()).thenReturn(5L);
        when(ragIndexServiceProvider.getIfAvailable()).thenReturn(ragIndexService);
        when(ragIndexService.enabled()).thenReturn(true);
        when(ragEmbeddingStateService.countSuccessPublic()).thenReturn(5L);
        when(ragIndexService.countIndexed()).thenReturn(5L);

        assertDoesNotThrow(task::checkConsistency);

        verify(ragIndexService).countIndexed();
    }

    @Test
    @DisplayName("向量巡检：数量不一致只记录告警，不抛异常")
    @SuppressWarnings("unchecked")
    void checkConsistency_ragMismatchDoesNotThrow() {
        when(pictureService.count(any(Wrapper.class))).thenReturn(5L);
        when(pictureSearchService.countIndexed()).thenReturn(5L);
        when(ragIndexServiceProvider.getIfAvailable()).thenReturn(ragIndexService);
        when(ragIndexService.enabled()).thenReturn(true);
        when(ragEmbeddingStateService.countSuccessPublic()).thenReturn(5L);
        when(ragIndexService.countIndexed()).thenReturn(4L);

        assertDoesNotThrow(task::checkConsistency);
    }

    @Test
    @DisplayName("向量巡检：空实现时不查向量状态表")
    @SuppressWarnings("unchecked")
    void checkConsistency_ragNoopSkipsStateTable() {
        when(pictureService.count(any(Wrapper.class))).thenReturn(5L);
        when(pictureSearchService.countIndexed()).thenReturn(5L);
        when(ragIndexServiceProvider.getIfAvailable()).thenReturn(ragIndexService);
        when(ragIndexService.enabled()).thenReturn(false);

        assertDoesNotThrow(task::checkConsistency);

        verifyNoInteractions(ragEmbeddingStateService);
    }
}

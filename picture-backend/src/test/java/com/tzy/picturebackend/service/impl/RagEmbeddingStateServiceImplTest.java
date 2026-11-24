package com.tzy.picturebackend.service.impl;

import com.tzy.picturebackend.mapper.PictureEmbeddingMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 向量化状态服务：UPSERT 参数、失败截断与空集合保护。
 */
class RagEmbeddingStateServiceImplTest {

    private PictureEmbeddingMapper mapper;

    private RagEmbeddingStateServiceImpl stateService;

    @BeforeEach
    void setUp() {
        mapper = mock(PictureEmbeddingMapper.class);
        stateService = new RagEmbeddingStateServiceImpl(mapper);
    }

    @Test
    @DisplayName("读取：id 为空时不查库")
    void get_nullIdSkipsDatabase() {
        assertNull(stateService.get(null));

        verifyNoInteractions(mapper);
    }

    @Test
    @DisplayName("成功：把 hash、模型、版本原样落表")
    void markTextSuccess_delegates() {
        stateService.markTextSuccess(1L, "hash", "text-embedding-v4", "v1");

        verify(mapper).markTextSuccess(1L, "hash", "text-embedding-v4", "v1");
    }

    @Test
    @DisplayName("失败：只标记失败的一侧，另一侧保持待处理")
    void markFailure_setsStatusesAndTruncatesError() {
        String longError = "x".repeat(600);

        stateService.markFailure(1L, true, false, longError);

        verify(mapper).markFailure(1L, true, false, 2, 0, "x".repeat(512));
    }

    @Test
    @DisplayName("失败：空错误写入可检索的占位符")
    void markFailure_blankErrorUsesPlaceholder() {
        stateService.markFailure(1L, false, true, " ");

        verify(mapper).markFailure(1L, false, true, 0, 2, "未知错误");
    }

    @Test
    @DisplayName("批量删除：空集合不触发 SQL")
    void deleteByPictureIds_emptySkipsDatabase() {
        stateService.deleteByPictureIds(null);

        verifyNoInteractions(mapper);
    }

    @Test
    @DisplayName("待处理数：直接读 Mapper 统计")
    void countPendingPublic_delegates() {
        when(mapper.countPendingPublic()).thenReturn(7L);

        assertEquals(7L, stateService.countPendingPublic());
    }
}

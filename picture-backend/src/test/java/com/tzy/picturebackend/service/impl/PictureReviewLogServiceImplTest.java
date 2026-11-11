package com.tzy.picturebackend.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.tzy.picturebackend.mapper.PictureReviewLogMapper;
import com.tzy.picturebackend.model.entity.PictureReviewLog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 审核记录服务测试
 */
class PictureReviewLogServiceImplTest {

    private PictureReviewLogServiceImpl reviewLogService;

    private PictureReviewLogMapper reviewLogMapper;

    @BeforeEach
    void setUp() {
        reviewLogService = new PictureReviewLogServiceImpl();
        reviewLogMapper = mock(PictureReviewLogMapper.class);
        ReflectionTestUtils.setField(reviewLogService, "baseMapper", reviewLogMapper);
    }

    @Test
    @DisplayName("记录审核：写入图片、审核人、状态与意见")
    void record_savesReviewLog() {
        when(reviewLogMapper.insert(any(PictureReviewLog.class))).thenReturn(1);

        reviewLogService.record(1L, 99L, 2, "内容不合规");

        ArgumentCaptor<PictureReviewLog> captor = ArgumentCaptor.forClass(PictureReviewLog.class);
        verify(reviewLogMapper).insert(captor.capture());
        PictureReviewLog saved = captor.getValue();
        assertEquals(Long.valueOf(1L), saved.getPictureId());
        assertEquals(Long.valueOf(99L), saved.getReviewerId());
        assertEquals(Integer.valueOf(2), saved.getReviewStatus());
        assertEquals("内容不合规", saved.getReviewMessage());
        assertNotNull(saved.getCreateTime());
    }

    @Test
    @DisplayName("记录审核：数据库异常不影响调用方")
    void record_swallowsDatabaseException() {
        when(reviewLogMapper.insert(any(PictureReviewLog.class))).thenThrow(new RuntimeException("db down"));

        reviewLogService.record(1L, 99L, 1, null);
    }

    @Test
    @DisplayName("查询历史：按图片 id 返回记录")
    void listByPictureId_returnsLogs() {
        PictureReviewLog log = new PictureReviewLog();
        log.setId(1L);
        when(reviewLogMapper.selectList(any(Wrapper.class))).thenReturn(List.of(log));

        List<PictureReviewLog> logs = reviewLogService.listByPictureId(1L);

        assertEquals(1, logs.size());
        assertEquals(Long.valueOf(1L), logs.get(0).getId());
    }
}

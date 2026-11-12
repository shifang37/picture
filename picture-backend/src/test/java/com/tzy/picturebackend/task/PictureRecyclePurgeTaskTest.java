package com.tzy.picturebackend.task;

import com.tzy.picturebackend.config.PictureRecycleProperties;
import com.tzy.picturebackend.service.PictureService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Date;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 回收站到期清理任务：开关、保留期计算、分批与异常兜底
 */
class PictureRecyclePurgeTaskTest {

    private PictureService pictureService;

    private PictureRecycleProperties properties;

    private PictureRecyclePurgeTask task;

    @BeforeEach
    void setUp() {
        pictureService = mock(PictureService.class);
        properties = new PictureRecycleProperties();
        properties.setRetentionDays(7);
        properties.setPurgeBatchSize(100);
        task = new PictureRecyclePurgeTask(pictureService, properties);
    }

    @Test
    @DisplayName("开关关闭：不查询、不清理")
    void purge_disabledSkips() {
        properties.setPurgeEnabled(false);

        task.purgeExpiredPictures();

        verifyNoInteractions(pictureService);
    }

    @Test
    @DisplayName("清理：按 7 天保留期计算截止时间，按批调用 service")
    void purge_usesRetentionDays() {
        when(pictureService.purgeExpiredPictures(any(Date.class), anyInt())).thenReturn(3);
        long expectedCutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(7);

        task.purgeExpiredPictures();

        ArgumentCaptor<Date> cutoffCaptor = ArgumentCaptor.forClass(Date.class);
        verify(pictureService).purgeExpiredPictures(cutoffCaptor.capture(), eq(100));
        long actualCutoff = cutoffCaptor.getValue().getTime();
        assertTrue(Math.abs(actualCutoff - expectedCutoff) < TimeUnit.SECONDS.toMillis(5),
                "截止时间应约为 7 天前，实际: " + new Date(actualCutoff));
    }

    @Test
    @DisplayName("清理：一批处理满后继续处理下一批，不满即停止")
    void purge_continuesUntilPartialBatch() {
        when(pictureService.purgeExpiredPictures(any(Date.class), anyInt()))
                .thenReturn(100, 2);

        task.purgeExpiredPictures();

        verify(pictureService, times(2)).purgeExpiredPictures(any(Date.class), eq(100));
    }

    @Test
    @DisplayName("清理：积压过多时最多处理固定批次，避免长时间占用调度线程")
    void purge_capsBatchCount() {
        when(pictureService.purgeExpiredPictures(any(Date.class), anyInt())).thenReturn(100);

        task.purgeExpiredPictures();

        verify(pictureService, times(PictureRecyclePurgeTask.MAX_BATCHES))
                .purgeExpiredPictures(any(Date.class), eq(100));
    }

    @Test
    @DisplayName("清理：单批异常只记录日志，不中断调度")
    void purge_swallowsFailure() {
        when(pictureService.purgeExpiredPictures(any(Date.class), anyInt()))
                .thenThrow(new RuntimeException("db down"));

        assertDoesNotThrow(task::purgeExpiredPictures);

        verify(pictureService).purgeExpiredPictures(any(Date.class), eq(100));
    }
}

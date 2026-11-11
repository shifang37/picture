package com.tzy.picturebackend.metrics;

import com.tzy.picturebackend.service.PictureService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 待审核数量定时刷新测试：数据库异常时保留上一次的值
 */
class PictureReviewPendingMetricsTest {

    private PictureService pictureService;

    private SimpleMeterRegistry registry;

    private PictureReviewPendingMetrics pendingMetrics;

    @BeforeEach
    void setUp() {
        pictureService = mock(PictureService.class);
        registry = new SimpleMeterRegistry();
        pendingMetrics = new PictureReviewPendingMetrics(pictureService,
                new PictureMetrics(registry));
    }

    @Test
    @DisplayName("刷新：把待审核数量写入 Gauge")
    void refresh_setsGauge() {
        when(pictureService.count(any())).thenReturn(6L);

        pendingMetrics.refresh();

        assertEquals(6.0, registry.get(PictureMetrics.REVIEW_PENDING).gauge().value());
    }

    @Test
    @DisplayName("刷新失败：保留上一次的值，不抛出")
    void refresh_keepsLastValueOnFailure() {
        when(pictureService.count(any())).thenReturn(6L);
        pendingMetrics.refresh();

        when(pictureService.count(any())).thenThrow(new RuntimeException("db down"));
        pendingMetrics.refresh();

        assertEquals(6.0, registry.get(PictureMetrics.REVIEW_PENDING).gauge().value());
    }
}

package com.tzy.picturebackend.task;

import com.tzy.picturebackend.metrics.PictureMetrics;
import com.tzy.picturebackend.service.RagIndexService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 向量索引健康巡检：只读实现状态，不写索引；ES 异常时按 stale 处理。
 */
class RagIndexHealthTaskTest {

    private ObjectProvider<RagIndexService> provider;

    private RagIndexService ragIndexService;

    private SimpleMeterRegistry registry;

    private PictureMetrics pictureMetrics;

    private RagIndexHealthTask task;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        provider = mock(ObjectProvider.class);
        ragIndexService = mock(RagIndexService.class);
        registry = new SimpleMeterRegistry();
        pictureMetrics = new PictureMetrics(registry);
        task = new RagIndexHealthTask(provider, pictureMetrics);
    }

    @Test
    @DisplayName("RAG 关闭（没有真实实现）时不注册指标")
    void noRealService_doesNotRegisterGauge() {
        when(provider.getIfAvailable()).thenReturn(null);

        task.refreshStaleGauge();

        assertNull(staleGaugeValue());
        verify(ragIndexService, never()).indexName();
    }

    @Test
    @DisplayName("别名健康时 Gauge=0，健康检查只读 indexName/stale")
    void healthyAlias_setsGaugeToZero() {
        when(provider.getIfAvailable()).thenReturn(ragIndexService);
        when(ragIndexService.enabled()).thenReturn(true);
        when(ragIndexService.indexName()).thenReturn("picture_vec_v1");
        when(ragIndexService.stale()).thenReturn(false);

        task.refreshStaleGauge();

        assertEquals(Double.valueOf(0.0), staleGaugeValue());
        verify(ragIndexService).indexName();
        verify(ragIndexService).stale();
    }

    @Test
    @DisplayName("别名不兼容时 Gauge=1，供 reindex 告警使用")
    void staleAlias_setsGaugeToOne() {
        when(provider.getIfAvailable()).thenReturn(ragIndexService);
        when(ragIndexService.enabled()).thenReturn(true);
        when(ragIndexService.indexName()).thenReturn("picture_vec_v1");
        when(ragIndexService.stale()).thenReturn(true);

        task.refreshStaleGauge();

        assertEquals(Double.valueOf(1.0), staleGaugeValue());
    }

    @Test
    @DisplayName("读别名抛异常时按 stale 处理，不让巡检线程退出")
    void failure_setsGaugeToOne() {
        when(provider.getIfAvailable()).thenReturn(ragIndexService);
        when(ragIndexService.enabled()).thenReturn(true);
        when(ragIndexService.indexName()).thenThrow(new IllegalStateException("ES 不可达"));

        task.refreshStaleGauge();

        assertEquals(Double.valueOf(1.0), staleGaugeValue());
    }

    /**
     * 未注册时返回 null；已注册时返回当前值。
     */
    private Double staleGaugeValue() {
        var gauge = registry.find(PictureMetrics.RAG_INDEX_STALE).gauge();
        return gauge == null ? null : gauge.value();
    }
}

package com.tzy.picturebackend.task;

import com.tzy.picturebackend.metrics.PictureMetrics;
import com.tzy.picturebackend.service.RagIndexService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 向量索引别名健康巡检：把 {@code RagStatusVO.stale} 变成可告警的 Gauge。
 * <p>
 * 只在 RAG 真正启用（真实 {@link RagIndexService} 存在且 {@code enabled()}）时注册
 * {@code picture.rag.index.stale}，关闭 RAG 时不会多出任何新指标。
 * 调用 {@code indexName()} 会触发一次别名解析与维度校验，别名被外部改坏时
 * 实现内部会置 {@code stale=true}，这里再把它同步到 Gauge。
 * <p>
 * 默认每 5 分钟检查一次；ES 不可达时把 Gauge 置 1 并记 warn，恢复后自动回到 0。
 */
@Component
@Slf4j
public class RagIndexHealthTask {

    private final ObjectProvider<RagIndexService> ragIndexServiceProvider;

    private final PictureMetrics pictureMetrics;

    private volatile boolean gaugeRegistered;

    public RagIndexHealthTask(ObjectProvider<RagIndexService> ragIndexServiceProvider,
                              PictureMetrics pictureMetrics) {
        this.ragIndexServiceProvider = ragIndexServiceProvider;
        this.pictureMetrics = pictureMetrics;
    }

    @Scheduled(
            initialDelayString = "${app.rag.index.health-check-initial-delay-millis:30000}",
            fixedDelayString = "${app.rag.index.health-check-millis:300000}")
    public void refreshStaleGauge() {
        RagIndexService ragIndexService = ragIndexServiceProvider.getIfAvailable();
        if (ragIndexService == null || !ragIndexService.enabled()) {
            return;
        }
        if (!gaugeRegistered) {
            pictureMetrics.registerRagIndexStaleGauge();
            gaugeRegistered = true;
        }
        try {
            // indexName() 会校验别名是否只指向一个兼容索引，并把结果写进 stale 状态
            ragIndexService.indexName();
            pictureMetrics.setRagIndexStale(ragIndexService.stale());
        } catch (RuntimeException e) {
            // 读不到别名时不能当作健康：置 1 让告警提示运维检查 ES 与索引
            pictureMetrics.setRagIndexStale(true);
            log.warn("向量索引健康巡检失败，暂按 stale 处理: {}", e.getMessage());
        }
    }
}

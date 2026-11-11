package com.tzy.picturebackend.metrics;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.tzy.picturebackend.model.entity.Picture;
import com.tzy.picturebackend.model.enums.PictureReviewStatusEnum;
import com.tzy.picturebackend.service.PictureService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 定时刷新「待审核图片数量」指标。
 * <p>
 * 指标本身是 Gauge（抓取时不查库），由这里每分钟查一次数据库并更新，
 * 避免 Prometheus 高频抓取把 COUNT 查询放大。默认开启，可用
 * {@code app.metrics.review-pending.enabled=false} 关闭。
 */
@Component
@Slf4j
@ConditionalOnProperty(prefix = "app.metrics.review-pending", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class PictureReviewPendingMetrics {

    private final PictureService pictureService;

    private final PictureMetrics pictureMetrics;

    public PictureReviewPendingMetrics(PictureService pictureService, PictureMetrics pictureMetrics) {
        this.pictureService = pictureService;
        this.pictureMetrics = pictureMetrics;
    }

    /**
     * 启动完成后先刷新一次，避免第一次抓取前一直显示 0
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        refresh();
    }

    /**
     * 默认每 60 秒刷新一次
     */
    @Scheduled(
            fixedDelayString = "${app.metrics.review-pending.interval-ms:60000}",
            initialDelayString = "${app.metrics.review-pending.initial-delay-ms:15000}")
    public void refresh() {
        try {
            long pending = pictureService.count(new QueryWrapper<Picture>()
                    .eq("reviewStatus", PictureReviewStatusEnum.REVIEWING.getValue()));
            pictureMetrics.setReviewPending(pending);
        } catch (RuntimeException e) {
            // 数据库暂时不可用时保留上一次的值，不能让监控把应用拖垮
            log.warn("刷新待审核图片数量失败", e);
        }
    }
}

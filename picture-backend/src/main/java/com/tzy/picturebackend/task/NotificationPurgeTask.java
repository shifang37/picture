package com.tzy.picturebackend.task;

import com.tzy.picturebackend.config.NotificationProperties;
import com.tzy.picturebackend.service.NotificationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.concurrent.TimeUnit;

/**
 * 已读通知保留期清理任务。
 * <p>
 * 只清理「已读且超过保留期」的通知：未读通知永远保留，避免用户错过审核结论。
 * 每天 04:10 执行一次，单次最多处理 {@link #MAX_BATCHES} 批，避免积压时长时间占用调度线程。
 * 多实例同时执行是安全的：删除条件（isRead = 1 且 createTime 超期）本身幂等。
 */
@Component
@Slf4j
public class NotificationPurgeTask {

    /**
     * 单次调度最多处理的批次
     */
    static final int MAX_BATCHES = 10;

    private final NotificationService notificationService;

    private final NotificationProperties notificationProperties;

    public NotificationPurgeTask(NotificationService notificationService,
                                 NotificationProperties notificationProperties) {
        this.notificationService = notificationService;
        this.notificationProperties = notificationProperties;
    }

    @Scheduled(cron = "0 10 4 * * ?")
    public void purgeExpiredNotifications() {
        int retentionDays = Math.max(0, notificationProperties.getRetentionDays());
        int batchSize = Math.max(1, notificationProperties.getPurgeBatchSize());
        Date before = new Date(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(retentionDays));
        int total = 0;
        for (int batch = 0; batch < MAX_BATCHES; batch++) {
            int purged;
            try {
                purged = notificationService.purgeReadBefore(before, batchSize);
            } catch (RuntimeException e) {
                log.error("已读通知清理失败，等待下一轮重试, before = {}", before, e);
                return;
            }
            total += purged;
            if (purged < batchSize) {
                break;
            }
        }
        if (total > 0) {
            log.info("已读通知清理完成, retentionDays = {}, purged = {}", retentionDays, total);
        }
    }
}

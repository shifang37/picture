package com.tzy.picturebackend.task;

import com.tzy.picturebackend.config.PictureRecycleProperties;
import com.tzy.picturebackend.service.PictureService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.concurrent.TimeUnit;

/**
 * 回收站到期物理清理任务。
 * <p>
 * 删除图片时只移入回收站；图片在回收站中保留 {@code app.recycle.retention-days} 天（默认 7 天），
 * 到期后由本任务先删除 COS 对象，再物理删除数据库行。审核日志不参与清理。
 * <p>
 * 清理按批执行，每批最多 {@code app.recycle.purge-batch-size} 条；COS 删除失败的图片会保留到下一轮，
 * 因此重复执行是安全的，多实例同时执行也不会产生错误结果。
 */
@Component
@Slf4j
public class PictureRecyclePurgeTask {

    /**
     * 单次调度最多处理的批次，避免积压时长时间占用调度线程
     */
    static final int MAX_BATCHES = 10;

    private final PictureService pictureService;

    private final PictureRecycleProperties pictureRecycleProperties;

    public PictureRecyclePurgeTask(PictureService pictureService,
                                   PictureRecycleProperties pictureRecycleProperties) {
        this.pictureService = pictureService;
        this.pictureRecycleProperties = pictureRecycleProperties;
    }

    /**
     * 启动 2 分钟后执行，此后每小时执行一次
     */
    @Scheduled(initialDelay = 120_000, fixedDelay = 3_600_000)
    public void purgeExpiredPictures() {
        if (!pictureRecycleProperties.isPurgeEnabled()) {
            return;
        }
        int retentionDays = Math.max(0, pictureRecycleProperties.getRetentionDays());
        int batchSize = Math.max(1, pictureRecycleProperties.getPurgeBatchSize());
        Date before = new Date(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(retentionDays));
        int total = 0;
        for (int batch = 0; batch < MAX_BATCHES; batch++) {
            int purged;
            try {
                purged = pictureService.purgeExpiredPictures(before, batchSize);
            } catch (RuntimeException e) {
                log.error("回收站到期清理失败，等待下一轮重试, before = {}", before, e);
                return;
            }
            total += purged;
            if (purged < batchSize) {
                break;
            }
        }
        if (total > 0) {
            log.info("回收站到期清理完成, retentionDays = {}, purged = {}", retentionDays, total);
        }
    }
}

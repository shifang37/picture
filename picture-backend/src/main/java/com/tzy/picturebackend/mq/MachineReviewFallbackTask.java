package com.tzy.picturebackend.mq;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.tzy.picturebackend.config.MachineReviewProperties;
import com.tzy.picturebackend.event.PictureEventPublisher;
import com.tzy.picturebackend.model.entity.Picture;
import com.tzy.picturebackend.model.enums.PictureReviewStatusEnum;
import com.tzy.picturebackend.service.PictureService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * 机审兜底任务。
 * <p>
 * 每 5 分钟扫描「创建超过 5 分钟、仍待审核、机审字段为空」的图片，重新发布 {@code picture.uploaded}。
 * 用于覆盖消息投递失败、消费者异常进死信、RabbitMQ 中断等场景下遗漏的图片。
 * <p>
 * 多实例部署时用 Redis {@code SET NX EX} 互斥，只有一个实例执行扫描；锁不主动释放，
 * 4 分钟后自然过期，下一次扫描重新抢锁。
 */
@Component
@Slf4j
public class MachineReviewFallbackTask {

    /**
     * 多实例互斥锁
     */
    static final String LOCK_KEY = "picture:machine-review:fallback:lock";

    /**
     * 锁的保留时间：略小于扫描间隔，避免同一实例长期持锁
     */
    static final Duration LOCK_TTL = Duration.ofMinutes(4);

    /**
     * 只补发创建超过该时长的图片，给正常消息留出处理时间
     */
    static final Duration STALE_THRESHOLD = Duration.ofMinutes(5);

    /**
     * 单次最多补发的图片数，避免一次扫描拖住调度线程
     */
    static final int BATCH_SIZE = 100;

    private final PictureService pictureService;

    private final PictureEventPublisher pictureEventPublisher;

    private final StringRedisTemplate stringRedisTemplate;

    private final MachineReviewProperties machineReviewProperties;

    public MachineReviewFallbackTask(PictureService pictureService,
                                     PictureEventPublisher pictureEventPublisher,
                                     StringRedisTemplate stringRedisTemplate,
                                     MachineReviewProperties machineReviewProperties) {
        this.pictureService = pictureService;
        this.pictureEventPublisher = pictureEventPublisher;
        this.stringRedisTemplate = stringRedisTemplate;
        this.machineReviewProperties = machineReviewProperties;
    }

    /**
     * 启动 1 分钟后开始，此后每 5 分钟执行一次
     */
    @Scheduled(initialDelay = 60_000, fixedDelay = 300_000)
    public void republishMissedUploads() {
        if (!machineReviewProperties.isEnabled()) {
            return;
        }
        if (!tryLock()) {
            return;
        }
        Date cutoff = new Date(System.currentTimeMillis() - STALE_THRESHOLD.toMillis());
        List<Picture> pictures;
        try {
            pictures = pictureService.list(new QueryWrapper<Picture>()
                    .eq("reviewStatus", PictureReviewStatusEnum.REVIEWING.getValue())
                    .isNull("machineReviewStatus")
                    .lt("createTime", cutoff)
                    .orderByAsc("createTime")
                    .last("LIMIT " + BATCH_SIZE));
        } catch (RuntimeException e) {
            log.error("机审兜底扫描查询失败", e);
            return;
        }
        if (pictures == null || pictures.isEmpty()) {
            return;
        }
        log.info("机审兜底扫描到 {} 张遗漏图片，重新发布上传事件", pictures.size());
        for (Picture picture : pictures) {
            pictureEventPublisher.pictureUploaded(picture.getId());
        }
    }

    /**
     * 抢扫描锁；Redis 异常时放弃本轮，避免多实例同时扫描
     */
    private boolean tryLock() {
        try {
            Boolean locked = stringRedisTemplate.opsForValue()
                    .setIfAbsent(LOCK_KEY, UUID.randomUUID().toString(), LOCK_TTL);
            return Boolean.TRUE.equals(locked);
        } catch (RuntimeException e) {
            log.error("机审兜底任务抢锁失败，跳过本轮", e);
            return false;
        }
    }
}

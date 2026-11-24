package com.tzy.picturebackend.service.impl;

import com.tzy.picturebackend.config.RagProperties;
import com.tzy.picturebackend.event.NotificationEventPublisher;
import com.tzy.picturebackend.metrics.PictureMetrics;
import com.tzy.picturebackend.model.dto.notification.NotificationMessage;
import com.tzy.picturebackend.model.dto.rag.RagDuplicateResult;
import com.tzy.picturebackend.model.dto.rag.RagSimilarMatch;
import com.tzy.picturebackend.model.entity.Picture;
import com.tzy.picturebackend.service.PictureService;
import com.tzy.picturebackend.service.RagDuplicateService;
import com.tzy.picturebackend.service.RagSearchService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

/**
 * 近重复检测实现：双阈值 + Redis 通知去重。
 * <p>
 * 阈值口径是**原始余弦相似度**（0.97 / 0.985），由 {@link RagSearchService#nearestSameUser}
 * 把 Elasticsearch 的归一化 kNN 分数还原后再比较（见 m4 报告「ES 分数换算」）。
 * <p>
 * 去重分两层：
 * <ol>
 *   <li>Redis {@code picture:rag:dup:<小id>:<大id>}，SETNX + 30 天 TTL：同一对图片只提示一次；</li>
 *   <li>通知表唯一键 {@code (userId, dedupeKey)} 兜底：即使 Redis 不可用或键被清掉，
 *       重复投递也不会产生第二条通知。</li>
 * </ol>
 */
@Slf4j
public class RagDuplicateServiceImpl implements RagDuplicateService {

    /**
     * 同一对图片的通知去重键前缀
     */
    static final String KEY_PREFIX = "picture:rag:dup:";

    private final RagSearchService ragSearchService;

    private final PictureService pictureService;

    private final NotificationEventPublisher notificationEventPublisher;

    private final StringRedisTemplate redisTemplate;

    private final RagProperties ragProperties;

    private final PictureMetrics pictureMetrics;

    public RagDuplicateServiceImpl(RagSearchService ragSearchService,
                                   PictureService pictureService,
                                   NotificationEventPublisher notificationEventPublisher,
                                   StringRedisTemplate redisTemplate,
                                   RagProperties ragProperties,
                                   PictureMetrics pictureMetrics) {
        this.ragSearchService = ragSearchService;
        this.pictureService = pictureService;
        this.notificationEventPublisher = notificationEventPublisher;
        this.redisTemplate = redisTemplate;
        this.ragProperties = ragProperties;
        this.pictureMetrics = pictureMetrics;
    }

    @Override
    public boolean enabled() {
        return ragSearchService.enabled();
    }

    @Override
    public RagDuplicateResult check(Picture picture) {
        if (picture == null || picture.getId() == null || picture.getUserId() == null) {
            return RagDuplicateResult.miss();
        }
        try {
            return detectAndNotify(picture);
        } catch (RuntimeException e) {
            // 检测失败不能影响图片索引与审核；下一次事件或重建会重新检测
            log.warn("近重复检测失败，跳过本次检测, pictureId={}", picture.getId(), e);
            pictureMetrics.ragDuplicate("error");
            return RagDuplicateResult.error();
        }
    }

    private RagDuplicateResult detectAndNotify(Picture picture) {
        RagSimilarMatch match = ragSearchService
                .nearestSameUser(picture.getId(), picture.getUserId())
                .orElse(null);
        if (match == null) {
            pictureMetrics.ragDuplicate("miss");
            return RagDuplicateResult.miss();
        }

        RagProperties.Duplicate config = ragProperties.getDuplicate();
        double cosine = match.cosine();
        if (cosine < config.getSuspectThreshold()) {
            log.debug("近重复检测未命中, pictureId={}, nearest={}, cosine={}",
                    picture.getId(), match.pictureId(), cosine);
            pictureMetrics.ragDuplicate("miss");
            return RagDuplicateResult.miss();
        }
        if (cosine < config.getDuplicateThreshold()) {
            // 疑似：只记指标，不打扰作者（模板化截图常落在这个区间）
            log.info("发现疑似重复（未达通知阈值）, pictureId={}, nearest={}, cosine={}",
                    picture.getId(), match.pictureId(), cosine);
            pictureMetrics.ragDuplicate("suspect");
            return RagDuplicateResult.suspect(match.pictureId(), cosine);
        }

        pictureMetrics.ragDuplicate("hit");
        Picture similar = pictureService.getById(match.pictureId());
        if (similar == null) {
            // 候选图在检测与回表之间被删除：不提示，下一次事件会重新检测
            log.warn("近重复候选图片已不存在, pictureId={}, nearest={}", picture.getId(), match.pictureId());
            return RagDuplicateResult.hit(match.pictureId(), cosine, false);
        }
        if (!markNotified(picture.getId(), match.pictureId())) {
            log.info("近重复已通知过，跳过重复提示, pictureId={}, nearest={}, cosine={}",
                    picture.getId(), match.pictureId(), cosine);
            return RagDuplicateResult.hit(match.pictureId(), cosine, false);
        }
        notificationEventPublisher.publish(NotificationMessage.duplicate(
                picture.getUserId(), picture.getId(), picture.getName(),
                similar.getId(), similar.getName()));
        log.info("近重复提示已发送, pictureId={}, nearest={}, cosine={}",
                picture.getId(), match.pictureId(), cosine);
        return RagDuplicateResult.hit(match.pictureId(), cosine, true);
    }

    /**
     * 标记「这一对图片已经通知过」。
     * <p>
     * Redis 不可用时返回 true（继续发通知）：通知表的唯一键仍会拦截重复写入，
     * 不能因为缓存故障让用户永远收不到提示。
     */
    private boolean markNotified(Long pictureId, Long similarPictureId) {
        long small = Math.min(pictureId, similarPictureId);
        long large = Math.max(pictureId, similarPictureId);
        String key = KEY_PREFIX + small + ":" + large;
        try {
            Boolean first = redisTemplate.opsForValue().setIfAbsent(key, "1",
                    Duration.ofDays(Math.max(1, ragProperties.getDuplicate().getNotifyTtlDays())));
            return !Boolean.FALSE.equals(first);
        } catch (RuntimeException e) {
            log.warn("近重复去重键写入失败，改由通知表唯一键兜底, key={}", key, e);
            return true;
        }
    }
}

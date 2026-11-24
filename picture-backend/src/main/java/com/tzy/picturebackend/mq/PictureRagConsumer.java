package com.tzy.picturebackend.mq;

import cn.hutool.core.collection.CollUtil;
import com.tzy.picturebackend.event.PictureEvent;
import com.tzy.picturebackend.model.dto.rag.RagDuplicateResult;
import com.tzy.picturebackend.model.entity.Picture;
import com.tzy.picturebackend.model.enums.PictureReviewStatusEnum;
import com.tzy.picturebackend.service.PictureService;
import com.tzy.picturebackend.service.RagDuplicateService;
import com.tzy.picturebackend.service.RagIndexService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * RAG 向量索引同步队列消费者。
 * <p>
 * 与 {@link PictureSearchConsumer} 同构：消费 {@code picture.uploaded}、{@code picture.changed} 与
 * {@code picture.batch.changed}，按 id 批量回查数据库；已通过审核的图片写入向量索引，
 * 其余（待审核、已拒绝、已删除）一律删除文档与状态行。图片查不到时必须删除而不是跳过，
 * 否则已删除图片会一直留在向量索引里。
 * <p>
 * 单条失败不中断整批：失败已由 {@code RagIndexService} 写入 picture_embedding.lastError，
 * 消息照常确认，由全量重建兜底；因此 {@code app.rag.enabled=false} 时消费者直接跳过，
 * 不产生 DB 查询、embedding 调用或 ES 请求。
 * <p>
 * 图片向量写入成功后追加一次近重复检测（M4）：命中判重阈值时给作者发站内通知。
 * 检测失败只记日志，不影响图片本身的索引结果。
 */
@Component
@Slf4j
public class PictureRagConsumer {

    private final PictureService pictureService;

    private final RagIndexService ragIndexService;

    private final RagDuplicateService ragDuplicateService;

    public PictureRagConsumer(PictureService pictureService, RagIndexService ragIndexService,
                              RagDuplicateService ragDuplicateService) {
        this.pictureService = pictureService;
        this.ragIndexService = ragIndexService;
        this.ragDuplicateService = ragDuplicateService;
    }

    @RabbitListener(queues = PictureMqConfig.RAG_QUEUE)
    public void onPictureChanged(PictureEvent event) {
        handle(event);
    }

    /**
     * 处理一条向量索引事件；批量事件会同步其中的全部图片。
     *
     * @return 是否是一条可同步的事件；返回值用于测试，消息最终都会被确认
     */
    boolean handle(PictureEvent event) {
        if (!ragIndexService.enabled()) {
            // 未启用 RAG：不查库、不调用 embedding，直接把消息消费掉，避免队列堆积
            return false;
        }
        if (event == null || CollUtil.isEmpty(event.pictureIds())) {
            log.warn("收到不完整的图片事件，直接确认: {}", event);
            return false;
        }
        List<Long> pictureIds = event.pictureIds().stream()
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (pictureIds.isEmpty()) {
            log.warn("收到不完整的图片事件，直接确认: {}", event);
            return false;
        }
        Map<Long, Picture> pictureMap = pictureService.listByIds(pictureIds).stream()
                .collect(Collectors.toMap(Picture::getId, Function.identity(), (left, right) -> left));
        int failed = 0;
        for (Long pictureId : pictureIds) {
            try {
                Picture picture = pictureMap.get(pictureId);
                if (picture == null) {
                    ragIndexService.deletePicture(pictureId);
                    log.info("图片已删除，移除向量索引, pictureId={}, eventId={}",
                            pictureId, event.eventId());
                    continue;
                }
                if (Objects.equals(picture.getReviewStatus(), PictureReviewStatusEnum.PASS.getValue())) {
                    ragIndexService.indexPicture(picture);
                    // 索引成功后再检测：近重复查询读的是刚写入的图片向量
                    checkDuplicate(picture);
                } else {
                    // 待审核、已拒绝：不能留在公开向量索引里
                    ragIndexService.deletePicture(pictureId);
                }
                log.info("向量索引同步完成, pictureId={}, reviewStatus={}, eventId={}",
                        pictureId, picture.getReviewStatus(), event.eventId());
            } catch (RuntimeException e) {
                // 单条失败不影响整批：状态与错误已落表，消息确认后由重建兜底
                failed++;
                log.error("向量索引同步失败, pictureId={}, eventId={}", pictureId, event.eventId(), e);
            }
        }
        if (pictureIds.size() > 1) {
            log.info("批量向量索引同步完成, size={}, failed={}, eventId={}",
                    pictureIds.size(), failed, event.eventId());
        }
        return true;
    }

    /**
     * 近重复检测：只提示、不改变审核结论；异常不向上抛，避免把「检测失败」记成「索引失败」。
     */
    private void checkDuplicate(Picture picture) {
        if (!ragDuplicateService.enabled()) {
            return;
        }
        try {
            RagDuplicateResult result = ragDuplicateService.check(picture);
            if (result != null && result.notified()) {
                log.info("近重复检测命中并已提示作者, pictureId={}, nearest={}, cosine={}",
                        picture.getId(), result.similarPictureId(), result.cosine());
            }
        } catch (RuntimeException e) {
            log.warn("近重复检测异常，不影响图片索引, pictureId={}", picture.getId(), e);
        }
    }
}

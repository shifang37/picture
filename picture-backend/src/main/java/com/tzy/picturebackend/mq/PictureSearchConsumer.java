package com.tzy.picturebackend.mq;

import com.tzy.picturebackend.event.PictureEvent;
import com.tzy.picturebackend.manager.PictureListCache;
import com.tzy.picturebackend.model.entity.Picture;
import com.tzy.picturebackend.model.enums.PictureReviewStatusEnum;
import com.tzy.picturebackend.service.PictureSearchService;
import com.tzy.picturebackend.service.PictureService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * 搜索索引同步队列消费者。
 * <p>
 * 消费 {@code picture.uploaded} 与 {@code picture.changed}：按 id 回查数据库，
 * 已通过审核的图片写入索引，其余（待审核、已拒绝、已删除）一律按 id 删除索引文档。
 * 图片查不到时不能跳过，否则已删除图片会一直留在索引里。
 * <p>
 * {@code app.search.engine=mysql} 时注入的是空实现，消费者只做状态判断，不产生任何外部调用。
 */
@Component
@Slf4j
public class PictureSearchConsumer {

    private final PictureService pictureService;

    private final PictureSearchService pictureSearchService;

    private final PictureListCache pictureListCache;

    public PictureSearchConsumer(PictureService pictureService,
                                 PictureSearchService pictureSearchService,
                                 PictureListCache pictureListCache) {
        this.pictureService = pictureService;
        this.pictureSearchService = pictureSearchService;
        this.pictureListCache = pictureListCache;
    }

    @RabbitListener(queues = PictureMqConfig.SEARCH_QUEUE)
    public void onPictureChanged(PictureEvent event) {
        handle(event);
    }

    /**
     * 处理一条搜索索引事件。
     *
     * @return 是否是一条可同步的事件；返回值用于测试，消息最终都会被确认
     */
    boolean handle(PictureEvent event) {
        if (event == null || event.pictureId() == null) {
            log.warn("收到不完整的图片事件，直接确认: {}", event);
            return false;
        }
        Long pictureId = event.pictureId();
        Picture picture = pictureService.getById(pictureId);
        if (picture == null) {
            // 已逻辑删除：索引侧必须按 id 删除文档
            pictureSearchService.deletePicture(pictureId);
            invalidateListCache();
            log.info("图片已删除，移除搜索索引, pictureId={}, eventId={}", pictureId, event.eventId());
            return true;
        }
        if (Objects.equals(picture.getReviewStatus(), PictureReviewStatusEnum.PASS.getValue())) {
            pictureSearchService.indexPicture(picture);
        } else {
            // 待审核、已拒绝：不能留在公开索引里
            pictureSearchService.deletePicture(pictureId);
        }
        invalidateListCache();
        log.info("搜索索引同步完成, pictureId={}, reviewStatus={}, eventId={}",
                pictureId, picture.getReviewStatus(), event.eventId());
        return true;
    }

    /**
     * 索引更新后让公开列表缓存失效。
     * <p>
     * 审核/删除会先失效缓存再发事件，如果此时有请求把「索引尚未更新」的结果写进缓存，
     * 最长 5 分钟内都搜不到刚通过的图片；索引写完后再失效一次可以避免这个窗口。
     * MySQL 搜索模式下索引操作是空实现，不需要额外失效。
     */
    private void invalidateListCache() {
        if (pictureSearchService.enabled()) {
            pictureListCache.invalidate();
        }
    }
}

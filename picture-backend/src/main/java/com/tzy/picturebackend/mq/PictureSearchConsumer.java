package com.tzy.picturebackend.mq;

import com.tzy.picturebackend.event.PictureEvent;
import com.tzy.picturebackend.model.entity.Picture;
import com.tzy.picturebackend.service.PictureService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * 搜索索引同步队列消费者。
 * <p>
 * 消费 {@code picture.uploaded} 与 {@code picture.changed}：按 id 回查后更新索引。
 * 图片已被删除时 {@link PictureService#getById} 返回 null，此时仍然需要按 id 删除索引，
 * 因此「查不到」不能被当成可以忽略的消息。
 * <p>
 * 搜索能力（Elasticsearch 或 MySQL 全文索引）在后续任务中接入，这里先保留消费入口与删除语义。
 */
@Component
@Slf4j
public class PictureSearchConsumer {

    private final PictureService pictureService;

    public PictureSearchConsumer(PictureService pictureService) {
        this.pictureService = pictureService;
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
        Picture picture = pictureService.getById(event.pictureId());
        // 查不到代表图片已被逻辑删除：索引侧要按 id 删除文档，不能跳过
        boolean deleted = picture == null;
        log.info("收到搜索索引同步事件, pictureId={}, deleted={}, eventId={}",
                event.pictureId(), deleted, event.eventId());
        return true;
    }
}

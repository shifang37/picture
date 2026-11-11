package com.tzy.picturebackend.mq;

import com.tzy.picturebackend.event.PictureEvent;
import com.tzy.picturebackend.model.entity.Picture;
import com.tzy.picturebackend.service.PictureService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * AI 标签队列消费者。
 * <p>
 * 消费 {@code picture.moderated}：机审完成后才生成名称、简介、分类与标签建议。
 * 图片已被删除或消息重复投递时直接确认。
 * <p>
 * AI 能力（模型调用、结果落库）在后续任务中接入，这里先保留完整的消费入口与状态校验。
 */
@Component
@Slf4j
public class PictureAiConsumer {

    private final PictureService pictureService;

    public PictureAiConsumer(PictureService pictureService) {
        this.pictureService = pictureService;
    }

    @RabbitListener(queues = PictureMqConfig.AI_QUEUE)
    public void onPictureModerated(PictureEvent event) {
        handle(event);
    }

    /**
     * 处理一条 AI 标签事件。
     *
     * @return 是否命中了需要生成建议的图片；返回值用于测试，消息最终都会被确认
     */
    boolean handle(PictureEvent event) {
        if (event == null || event.pictureId() == null) {
            log.warn("收到不完整的图片事件，直接确认: {}", event);
            return false;
        }
        Picture picture = pictureService.getById(event.pictureId());
        if (picture == null) {
            log.info("图片不存在，丢弃 AI 标签事件, pictureId={}, eventId={}",
                    event.pictureId(), event.eventId());
            return false;
        }
        log.info("收到机审完成事件，等待生成 AI 建议, pictureId={}, eventId={}",
                event.pictureId(), event.eventId());
        return true;
    }
}

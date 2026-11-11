package com.tzy.picturebackend.mq;

import com.tzy.picturebackend.event.PictureEvent;
import com.tzy.picturebackend.model.entity.Picture;
import com.tzy.picturebackend.model.enums.PictureReviewStatusEnum;
import com.tzy.picturebackend.service.PictureService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * 机审队列消费者。
 * <p>
 * 消费 {@code picture.uploaded}：按 id 回查图片，只处理「仍然存在且仍待审核」的图片。
 * 已删除、已被人工审核或重复投递的消息直接确认，避免重复机审和无效重试。
 * <p>
 * 机审能力（调用数据万象并发布 {@code picture.moderated}）在后续任务中接入，这里先完成
 * 事件链路、状态判断和幂等语义。
 */
@Component
@Slf4j
public class PictureModerationConsumer {

    private final PictureService pictureService;

    public PictureModerationConsumer(PictureService pictureService) {
        this.pictureService = pictureService;
    }

    @RabbitListener(queues = PictureMqConfig.MODERATION_QUEUE)
    public void onPictureUploaded(PictureEvent event) {
        handle(event);
    }

    /**
     * 处理一条机审事件。
     *
     * @return 是否命中了需要机审的图片；返回值用于测试，消息最终都会被确认
     */
    boolean handle(PictureEvent event) {
        if (event == null || event.pictureId() == null) {
            log.warn("收到不完整的图片事件，直接确认: {}", event);
            return false;
        }
        Picture picture = pictureService.getById(event.pictureId());
        if (picture == null) {
            // 图片已删除或 id 不存在：确认后丢弃，不能反复重试
            log.info("图片不存在，丢弃机审事件, pictureId={}, eventId={}",
                    event.pictureId(), event.eventId());
            return false;
        }
        if (!Objects.equals(picture.getReviewStatus(), PictureReviewStatusEnum.REVIEWING.getValue())) {
            // 管理员已经提前审核，或消息被重复投递：不再重复机审
            log.info("图片已不处于待审核状态，跳动机审事件, pictureId={}, reviewStatus={}",
                    event.pictureId(), picture.getReviewStatus());
            return false;
        }
        log.info("收到待机审图片事件, pictureId={}, eventId={}", event.pictureId(), event.eventId());
        return true;
    }
}

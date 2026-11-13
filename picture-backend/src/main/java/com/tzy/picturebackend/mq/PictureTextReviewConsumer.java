package com.tzy.picturebackend.mq;

import cn.hutool.core.collection.CollUtil;
import com.tzy.picturebackend.config.TextReviewProperties;
import com.tzy.picturebackend.event.PictureEvent;
import com.tzy.picturebackend.model.entity.Picture;
import com.tzy.picturebackend.service.PictureService;
import com.tzy.picturebackend.service.TextReviewService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * 文本审核队列消费者。
 * <p>
 * 消费上传、编辑与批量编辑事件：按 id 回查图片，只处理「仍然存在、文本尚未审核」的图片。
 * 已删除、文审字段已有值或开关关闭时直接确认；名称/简介为空时由 service 跳过，不产生调用费用。
 */
@Component
@Slf4j
public class PictureTextReviewConsumer {

    private final PictureService pictureService;

    private final TextReviewService textReviewService;

    private final TextReviewProperties textReviewProperties;

    public PictureTextReviewConsumer(PictureService pictureService,
                                     TextReviewService textReviewService,
                                     TextReviewProperties textReviewProperties) {
        this.pictureService = pictureService;
        this.textReviewService = textReviewService;
        this.textReviewProperties = textReviewProperties;
    }

    @RabbitListener(queues = PictureMqConfig.TEXT_REVIEW_QUEUE)
    public void onPictureEvent(PictureEvent event) {
        handle(event);
    }

    /**
     * 处理一条图片事件。
     *
     * @return 是否触发了文审；返回值用于测试，消息最终都会被确认
     */
    boolean handle(PictureEvent event) {
        if (!textReviewProperties.isEnabled()) {
            // 关闭文审时不查库、不调用数据万象，行为与接入前一致
            log.debug("文本审核未启用，跳过文审事件, event={}", event);
            return false;
        }
        if (event == null || CollUtil.isEmpty(event.pictureIds())) {
            log.warn("收到不完整的图片事件，直接确认: {}", event);
            return false;
        }
        boolean reviewed = false;
        for (Long pictureId : event.pictureIds()) {
            if (pictureId == null) {
                continue;
            }
            Picture picture = pictureService.getById(pictureId);
            if (picture == null) {
                // 图片已删除或 id 不存在：确认后丢弃，不能反复重试
                log.info("图片不存在，丢弃文审事件, pictureId={}, eventId={}", pictureId, event.eventId());
                continue;
            }
            if (picture.getTextReviewStatus() != null) {
                // 已经审过：重复投递或与审核事件共用路由键，直接确认
                log.info("图片已完成文审，跳过文审事件, pictureId={}, textReviewStatus={}",
                        pictureId, picture.getTextReviewStatus());
                continue;
            }
            try {
                textReviewService.review(picture);
                reviewed = true;
            } catch (RuntimeException e) {
                // 交给 RabbitMQ 重试；重试耗尽进死信，与机审一致
                log.error("文审处理失败, pictureId={}, eventId={}", pictureId, event.eventId(), e);
                throw e;
            }
        }
        return reviewed;
    }
}

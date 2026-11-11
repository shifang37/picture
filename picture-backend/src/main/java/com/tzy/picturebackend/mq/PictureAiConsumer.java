package com.tzy.picturebackend.mq;

import com.tzy.picturebackend.config.AiProperties;
import com.tzy.picturebackend.config.MachineReviewProperties;
import com.tzy.picturebackend.event.PictureEvent;
import com.tzy.picturebackend.event.PictureEventType;
import com.tzy.picturebackend.model.entity.Picture;
import com.tzy.picturebackend.model.enums.MachineReviewStatusEnum;
import com.tzy.picturebackend.model.enums.PictureReviewStatusEnum;
import com.tzy.picturebackend.service.AiSuggestionService;
import com.tzy.picturebackend.service.PictureService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * AI 建议队列消费者。
 * <p>
 * 机审开启时以 {@code picture.moderated} 为准，只有机审为「正常」的图片才发送给第三方模型；
 * 管理员上传的图片会直接通过审核、不产生机审事件，因此这类图片在 {@code picture.uploaded}
 * 时直接生成。机审关闭时也直接消费 {@code picture.uploaded}。
 */
@Component
@Slf4j
public class PictureAiConsumer {

    private final PictureService pictureService;

    private final AiSuggestionService aiSuggestionService;

    private final AiProperties aiProperties;

    private final MachineReviewProperties machineReviewProperties;

    public PictureAiConsumer(PictureService pictureService,
                             AiSuggestionService aiSuggestionService,
                             AiProperties aiProperties,
                             MachineReviewProperties machineReviewProperties) {
        this.pictureService = pictureService;
        this.aiSuggestionService = aiSuggestionService;
        this.aiProperties = aiProperties;
        this.machineReviewProperties = machineReviewProperties;
    }

    @RabbitListener(queues = PictureMqConfig.AI_QUEUE)
    public void onPictureEvent(PictureEvent event) {
        handle(event);
    }

    /**
     * 处理一条 AI 建议事件。
     *
     * @return 是否触发了 AI 建议生成；返回值用于测试，消息最终都会被确认
     */
    boolean handle(PictureEvent event) {
        if (!aiProperties.isEnabled()) {
            log.debug("AI 建议未启用，跳过事件: {}", event);
            return false;
        }
        if (event == null || event.pictureId() == null) {
            log.warn("收到不完整的图片事件，直接确认: {}", event);
            return false;
        }
        Picture picture = pictureService.getById(event.pictureId());
        if (picture == null) {
            log.info("图片不存在，丢弃 AI 建议事件, pictureId={}, eventId={}",
                    event.pictureId(), event.eventId());
            return false;
        }
        if (Objects.equals(picture.getReviewStatus(), PictureReviewStatusEnum.REJECT.getValue())) {
            log.info("图片已拒绝，跳过 AI 建议, pictureId={}", event.pictureId());
            return false;
        }
        if (event.type() == PictureEventType.UPLOADED) {
            // 机审开启且图片仍在待审核：等 moderated 事件；管理员直传的图片已通过，直接生成
            if (machineReviewProperties.isEnabled()
                    && !Objects.equals(picture.getReviewStatus(), PictureReviewStatusEnum.PASS.getValue())) {
                log.info("机审开启，等待机审完成事件再生成 AI 建议, pictureId={}", event.pictureId());
                return false;
            }
        } else if (event.type() == PictureEventType.MODERATED) {
            if (!machineReviewProperties.isEnabled()) {
                log.debug("机审未启用，忽略 moderated 事件, pictureId={}", event.pictureId());
                return false;
            }
            // 只把机审为正常的图片发给第三方模型，疑似/违规内容不外发
            if (!Objects.equals(picture.getMachineReviewStatus(),
                    MachineReviewStatusEnum.NORMAL.getValue())) {
                log.info("机审结果不是正常，跳过 AI 建议, pictureId={}, machineReviewStatus={}",
                        event.pictureId(), picture.getMachineReviewStatus());
                return false;
            }
        } else {
            return false;
        }
        try {
            aiSuggestionService.generate(picture);
        } catch (RuntimeException e) {
            log.error("AI 建议处理失败, pictureId={}, eventId={}",
                    event.pictureId(), event.eventId(), e);
            throw e;
        }
        return true;
    }
}

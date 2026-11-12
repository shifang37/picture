package com.tzy.picturebackend.mq;

import com.tzy.picturebackend.config.MachineReviewProperties;
import com.tzy.picturebackend.event.PictureEvent;
import com.tzy.picturebackend.model.entity.Picture;
import com.tzy.picturebackend.model.enums.PictureReviewStatusEnum;
import com.tzy.picturebackend.service.MachineReviewService;
import com.tzy.picturebackend.service.PictureService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * 机审队列消费者。
 * <p>
 * 消费 {@code picture.uploaded}：按 id 回查图片，只处理「仍然存在、仍待审核且尚未机审」的图片。
 * 已删除、已被人工审核、机审字段已有值或机审开关关闭时直接确认，避免重复机审和无效重试。
 */
@Component
@Slf4j
public class PictureModerationConsumer {

    private final PictureService pictureService;

    private final MachineReviewService machineReviewService;

    private final MachineReviewProperties machineReviewProperties;

    public PictureModerationConsumer(PictureService pictureService,
                                     MachineReviewService machineReviewService,
                                     MachineReviewProperties machineReviewProperties) {
        this.pictureService = pictureService;
        this.machineReviewService = machineReviewService;
        this.machineReviewProperties = machineReviewProperties;
    }

    @RabbitListener(queues = PictureMqConfig.MODERATION_QUEUE)
    public void onPictureUploaded(PictureEvent event) {
        handle(event);
    }

    /**
     * 处理一条机审事件。
     *
     * @return 是否触发了机审；返回值用于测试，消息最终都会被确认
     */
    boolean handle(PictureEvent event) {
        if (!machineReviewProperties.isEnabled()) {
            // 关闭机审时不查库、不调用数据万象，行为与接入前一致
            log.debug("机审未启用，跳过机审事件, event={}", event);
            return false;
        }
        if (event == null || event.pictureIds() == null || event.pictureIds().size() != 1) {
            // 机审只处理单图事件；批量事件不会绑定到这个队列
            log.warn("收到不完整的图片事件，直接确认: {}", event);
            return false;
        }
        Long pictureId = event.pictureIds().get(0);
        Picture picture = pictureService.getById(pictureId);
        if (picture == null) {
            // 图片已删除或 id 不存在：确认后丢弃，不能反复重试
            log.info("图片不存在，丢弃机审事件, pictureId={}, eventId={}",
                    pictureId, event.eventId());
            return false;
        }
        if (!Objects.equals(picture.getReviewStatus(), PictureReviewStatusEnum.REVIEWING.getValue())) {
            // 管理员已经提前审核，或消息被重复投递：不再重复机审
            log.info("图片已不处于待审核状态，跳动机审事件, pictureId={}, reviewStatus={}",
                    pictureId, picture.getReviewStatus());
            return false;
        }
        if (picture.getMachineReviewStatus() != null) {
            // 机审字段已有值：重复投递，直接确认
            log.info("图片已完成机审，跳动机审事件, pictureId={}, machineReviewStatus={}",
                    pictureId, picture.getMachineReviewStatus());
            return false;
        }
        try {
            machineReviewService.review(picture);
        } catch (RuntimeException e) {
            // 交给 RabbitMQ 重试；重试耗尽进死信，兜底任务还会重新发布
            log.error("机审处理失败, pictureId={}, eventId={}", pictureId, event.eventId(), e);
            throw e;
        }
        return true;
    }
}

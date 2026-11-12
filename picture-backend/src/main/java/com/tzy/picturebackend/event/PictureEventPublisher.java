package com.tzy.picturebackend.event;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.Collection;

/**
 * 图片事件发布入口。
 * <p>
 * 业务代码只发布 Spring 应用事件，不直接依赖 RabbitMQ：这样 Service 的单元测试只需要验证事件是否发出，
 * 由 {@link PictureEventRelay} 负责转换成消息投递。RabbitMQ 不可用时事件发布本身也不会失败。
 */
@Component
public class PictureEventPublisher {

    private final ApplicationEventPublisher applicationEventPublisher;

    public PictureEventPublisher(ApplicationEventPublisher applicationEventPublisher) {
        this.applicationEventPublisher = applicationEventPublisher;
    }

    /**
     * 新上传或重传成功
     */
    public void pictureUploaded(Long pictureId) {
        publish(PictureEventType.UPLOADED, pictureId);
    }

    /**
     * 机审完成
     */
    public void pictureModerated(Long pictureId) {
        publish(PictureEventType.MODERATED, pictureId);
    }

    /**
     * 人工审核、编辑、删除等会改变图片内容或可见性的操作
     */
    public void pictureChanged(Long pictureId) {
        publish(PictureEventType.CHANGED, pictureId);
    }

    /**
     * 批量审核完成：整批只发一条事件，由消费者按 id 批量同步搜索索引
     */
    public void picturesChanged(Collection<Long> pictureIds) {
        if (pictureIds == null || pictureIds.isEmpty()) {
            return;
        }
        publish(PictureEventType.BATCH_CHANGED, pictureIds);
    }

    private void publish(PictureEventType type, Long pictureId) {
        applicationEventPublisher.publishEvent(PictureEvent.of(type, pictureId));
    }

    private void publish(PictureEventType type, Collection<Long> pictureIds) {
        applicationEventPublisher.publishEvent(PictureEvent.of(type, pictureIds));
    }
}

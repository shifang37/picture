package com.tzy.picturebackend.event;

import com.tzy.picturebackend.model.dto.notification.NotificationMessage;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 站内通知发布入口。
 * <p>
 * 业务代码（审核、重置密码、公告）只调用这里，不直接依赖 {@code NotificationService}：
 * 通知落库失败不会影响主流程，事务回滚时也不会留下「审核失败但通知已发」的脏数据。
 */
@Component
public class NotificationEventPublisher {

    private final ApplicationEventPublisher applicationEventPublisher;

    public NotificationEventPublisher(ApplicationEventPublisher applicationEventPublisher) {
        this.applicationEventPublisher = applicationEventPublisher;
    }

    /**
     * 发布一条通知
     */
    public void publish(NotificationMessage message) {
        if (message == null || message.getUserId() == null || message.getType() == null) {
            return;
        }
        applicationEventPublisher.publishEvent(new NotificationEvent(this, List.of(message)));
    }

    /**
     * 批量发布通知：一次事件、一次批量插入
     */
    public void publishAll(Collection<NotificationMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        List<NotificationMessage> valid = new ArrayList<>(messages.size());
        for (NotificationMessage message : messages) {
            if (message != null && message.getUserId() != null && message.getType() != null) {
                valid.add(message);
            }
        }
        if (!valid.isEmpty()) {
            applicationEventPublisher.publishEvent(new NotificationEvent(this, valid));
        }
    }
}

package com.tzy.picturebackend.event;

import com.tzy.picturebackend.model.dto.notification.NotificationMessage;
import org.springframework.context.ApplicationEvent;

import java.util.List;

/**
 * 站内通知事件。
 * <p>
 * 批量审核、批量公告只发一条事件，监听器一次批量插入，避免 100 次单条 insert。
 * 事件本身不携带事务语义：是否需要等事务提交由监听器的 {@code @TransactionalEventListener} 决定。
 */
public class NotificationEvent extends ApplicationEvent {

    private final List<NotificationMessage> messages;

    public NotificationEvent(Object source, List<NotificationMessage> messages) {
        super(source);
        this.messages = messages == null ? List.of() : List.copyOf(messages);
    }

    public List<NotificationMessage> getMessages() {
        return messages;
    }
}

package com.tzy.picturebackend.event;

import com.tzy.picturebackend.service.NotificationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 站内通知监听器。
 * <p>
 * {@code AFTER_COMMIT}：审核、重置密码等操作回滚时不会留下「操作失败但通知已发」；
 * {@code fallbackExecution = true}：机审、公告这类没有事务的调用方也能正常收到通知。
 * <p>
 * 通知写入失败只记日志，绝不向上抛出：通知是附属能力，不能反过来让审核失败。
 */
@Component
@Slf4j
public class NotificationEventListener {

    private final NotificationService notificationService;

    public NotificationEventListener(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onNotification(NotificationEvent event) {
        try {
            notificationService.notifyBatch(event.getMessages());
        } catch (Exception e) {
            log.error("写入站内通知失败, count = {}", event.getMessages().size(), e);
        }
    }
}

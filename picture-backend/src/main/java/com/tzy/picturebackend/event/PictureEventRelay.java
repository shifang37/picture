package com.tzy.picturebackend.event;

import com.tzy.picturebackend.mq.PictureMqConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 把 Spring 应用事件转发到 RabbitMQ。
 * <p>
 * 用 {@code @TransactionalEventListener(fallbackExecution = true)}：当前代码没有事务，事件会立即转发；
 * 将来给业务流程加上事务后，会自动变成「事务提交后再发」，避免消息先于数据落库。
 * <p>
 * 发送失败只记录日志，不能让上传、审核等主流程失败。
 */
@Component
@Slf4j
public class PictureEventRelay {

    private final RabbitTemplate rabbitTemplate;

    public PictureEventRelay(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    @TransactionalEventListener(fallbackExecution = true)
    public void relay(PictureEvent event) {
        CorrelationData correlationData = new CorrelationData(event.eventId());
        try {
            rabbitTemplate.convertAndSend(
                    PictureMqConfig.EVENT_EXCHANGE,
                    event.type().getRoutingKey(),
                    event,
                    correlationData);
        } catch (RuntimeException e) {
            // RabbitMQ 不可用时只丢弃这次投递，上传等主流程已经成功，不能因此回滚或报错
            log.error("图片事件投递失败, eventId={}, type={}, pictureIds={}",
                    event.eventId(), event.type(), event.pictureIds(), e);
        }
    }
}

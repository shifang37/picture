package com.tzy.picturebackend.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tzy.picturebackend.event.PictureEventType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.autoconfigure.amqp.RabbitTemplateCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 图片事件的消息队列拓扑与 RabbitTemplate 配置。
 * <p>
 * 拓扑（声明为 Declarable，应用启动时由 RabbitAdmin 自动创建，重复启动是幂等的）：
 * <pre>
 * picture.event (topic)
 *   ├─ picture.uploaded  → picture.moderation.q、picture.search.q
 *   ├─ picture.moderated → picture.ai.q
 *   └─ picture.changed   → picture.search.q
 *
 * 每个业务队列都绑定自己的死信队列 picture.*.q.dlq，交换机为 picture.dlx。
 * </pre>
 */
@Configuration
@Slf4j
public class PictureMqConfig {

    /**
     * 业务事件交换机
     */
    public static final String EVENT_EXCHANGE = "picture.event";

    /**
     * 死信交换机
     */
    public static final String DEAD_LETTER_EXCHANGE = "picture.dlx";

    /**
     * 机审队列
     */
    public static final String MODERATION_QUEUE = "picture.moderation.q";

    /**
     * AI 标签队列
     */
    public static final String AI_QUEUE = "picture.ai.q";

    /**
     * 搜索索引同步队列
     */
    public static final String SEARCH_QUEUE = "picture.search.q";

    /**
     * 死信队列后缀
     */
    public static final String DEAD_LETTER_QUEUE_SUFFIX = ".dlq";

    /**
     * 业务队列与死信队列的绑定，死信路由键与死信队列同名
     */
    public static String deadLetterRoutingKey(String queueName) {
        return queueName + DEAD_LETTER_QUEUE_SUFFIX;
    }

    @Bean
    public Declarables pictureEventDeclarables() {
        TopicExchange eventExchange = new TopicExchange(EVENT_EXCHANGE, true, false);
        TopicExchange deadLetterExchange = new TopicExchange(DEAD_LETTER_EXCHANGE, true, false);

        Queue moderationQueue = workQueue(MODERATION_QUEUE);
        Queue aiQueue = workQueue(AI_QUEUE);
        Queue searchQueue = workQueue(SEARCH_QUEUE);
        Queue moderationDeadLetterQueue = deadLetterQueue(MODERATION_QUEUE);
        Queue aiDeadLetterQueue = deadLetterQueue(AI_QUEUE);
        Queue searchDeadLetterQueue = deadLetterQueue(SEARCH_QUEUE);

        return new Declarables(
                eventExchange, deadLetterExchange,
                moderationQueue, aiQueue, searchQueue,
                moderationDeadLetterQueue, aiDeadLetterQueue, searchDeadLetterQueue,
                // 上传：机审与搜索都要处理
                BindingBuilder.bind(moderationQueue).to(eventExchange)
                        .with(PictureEventType.UPLOADED.getRoutingKey()),
                BindingBuilder.bind(searchQueue).to(eventExchange)
                        .with(PictureEventType.UPLOADED.getRoutingKey()),
                // 机审完成：触发 AI 标签建议
                BindingBuilder.bind(aiQueue).to(eventExchange)
                        .with(PictureEventType.MODERATED.getRoutingKey()),
                // 人工审核、编辑、删除：只需要更新搜索索引
                BindingBuilder.bind(searchQueue).to(eventExchange)
                        .with(PictureEventType.CHANGED.getRoutingKey()),
                // 每个业务队列的死信都进各自的 *.dlq
                BindingBuilder.bind(moderationDeadLetterQueue).to(deadLetterExchange)
                        .with(deadLetterRoutingKey(MODERATION_QUEUE)),
                BindingBuilder.bind(aiDeadLetterQueue).to(deadLetterExchange)
                        .with(deadLetterRoutingKey(AI_QUEUE)),
                BindingBuilder.bind(searchDeadLetterQueue).to(deadLetterExchange)
                        .with(deadLetterRoutingKey(SEARCH_QUEUE)));
    }

    /**
     * 消息体用 JSON：与 HTTP 接口共用同一套 Jackson 配置，排查时可直接在管理界面阅读
     */
    @Bean
    public MessageConverter pictureMessageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }

    /**
     * 发布确认与无法路由回调：失败只记录日志，不影响已经完成的业务操作
     */
    @Bean
    public RabbitTemplateCustomizer pictureRabbitTemplateCustomizer(MessageConverter pictureMessageConverter) {
        return template -> {
            template.setMessageConverter(pictureMessageConverter);
            template.setMandatory(true);
            template.setConfirmCallback((correlationData, ack, cause) -> {
                if (!ack) {
                    log.error("图片事件未被 RabbitMQ 确认, correlationId={}, cause={}",
                            correlationData == null ? null : correlationData.getId(), cause);
                }
            });
            template.setReturnsCallback(returned -> log.error(
                    "图片事件无法路由, exchange={}, routingKey={}, replyCode={}, replyText={}",
                    returned.getExchange(), returned.getRoutingKey(),
                    returned.getReplyCode(), returned.getReplyText()));
        };
    }

    /**
     * 业务队列：持久化，处理失败且重试耗尽的消息转入死信交换机
     */
    private static Queue workQueue(String queueName) {
        return QueueBuilder.durable(queueName)
                .deadLetterExchange(DEAD_LETTER_EXCHANGE)
                .deadLetterRoutingKey(deadLetterRoutingKey(queueName))
                .build();
    }

    /**
     * 死信队列：持久化，只接收消息，不再继续投递
     */
    private static Queue deadLetterQueue(String queueName) {
        return QueueBuilder.durable(deadLetterRoutingKey(queueName)).build();
    }
}

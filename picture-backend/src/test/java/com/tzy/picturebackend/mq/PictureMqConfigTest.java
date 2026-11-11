package com.tzy.picturebackend.mq;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PictureMqConfig 单元测试：不连接 Broker，只校验队列、交换机与绑定声明
 */
class PictureMqConfigTest {

    private final Declarables declarables = new PictureMqConfig().pictureEventDeclarables();

    @Test
    @DisplayName("拓扑：三个业务队列 + 三个死信队列，业务队列都配置了死信交换机")
    void declaresWorkQueuesAndDeadLetterQueues() {
        Map<String, Queue> queues = declarables.getDeclarablesByType(Queue.class).stream()
                .collect(Collectors.toMap(Queue::getName, Function.identity()));

        assertEquals(6, queues.size());
        for (String queueName : List.of(PictureMqConfig.MODERATION_QUEUE,
                PictureMqConfig.AI_QUEUE, PictureMqConfig.SEARCH_QUEUE)) {
            Queue workQueue = queues.get(queueName);
            assertTrue(workQueue != null, "缺少业务队列: " + queueName);
            assertTrue(workQueue.isDurable(), "业务队列应持久化: " + queueName);
            assertEquals(PictureMqConfig.DEAD_LETTER_EXCHANGE,
                    workQueue.getArguments().get("x-dead-letter-exchange"));
            assertEquals(PictureMqConfig.deadLetterRoutingKey(queueName),
                    workQueue.getArguments().get("x-dead-letter-routing-key"));

            Queue deadLetterQueue = queues.get(PictureMqConfig.deadLetterRoutingKey(queueName));
            assertTrue(deadLetterQueue != null, "缺少死信队列: " + queueName);
            assertTrue(deadLetterQueue.isDurable(), "死信队列应持久化: " + queueName);
            assertNull(deadLetterQueue.getArguments().get("x-dead-letter-exchange"),
                    "死信队列不应再配置死信交换机: " + queueName);
        }
    }

    @Test
    @DisplayName("绑定：上传进机审与搜索，机审完成进 AI，变更只进搜索")
    void bindsRoutingKeysToExpectedQueues() {
        Set<String> bindings = declarables.getDeclarablesByType(Binding.class).stream()
                .map(binding -> binding.getExchange() + " " + binding.getRoutingKey()
                        + " -> " + binding.getDestination())
                .collect(Collectors.toSet());

        assertEquals(Set.of(
                "picture.event picture.uploaded -> picture.moderation.q",
                "picture.event picture.uploaded -> picture.search.q",
                "picture.event picture.uploaded -> picture.ai.q",
                "picture.event picture.moderated -> picture.ai.q",
                "picture.event picture.changed -> picture.search.q",
                "picture.dlx picture.moderation.q.dlq -> picture.moderation.q.dlq",
                "picture.dlx picture.ai.q.dlq -> picture.ai.q.dlq",
                "picture.dlx picture.search.q.dlq -> picture.search.q.dlq"), bindings);
    }

    @Test
    @DisplayName("交换机：业务交换机与死信交换机都是持久化的 topic 交换机")
    void declaresTopicExchanges() {
        Map<String, TopicExchange> exchanges = declarables.getDeclarablesByType(TopicExchange.class).stream()
                .collect(Collectors.toMap(TopicExchange::getName, Function.identity()));

        assertEquals(Set.of(PictureMqConfig.EVENT_EXCHANGE, PictureMqConfig.DEAD_LETTER_EXCHANGE),
                exchanges.keySet());
        assertTrue(exchanges.values().stream().allMatch(TopicExchange::isDurable));
    }
}

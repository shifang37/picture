package com.tzy.picturebackend.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tzy.picturebackend.mq.PictureMqConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * PictureEventRelay 单元测试：路由键、确认标识、消息体字段与 Broker 不可用时的降级
 */
class PictureEventRelayTest {

    private RabbitTemplate rabbitTemplate;

    private PictureEventRelay relay;

    @BeforeEach
    void setUp() {
        rabbitTemplate = mock(RabbitTemplate.class);
        relay = new PictureEventRelay(rabbitTemplate);
    }

    @Test
    @DisplayName("转发：发到 picture.event，按事件类型选择路由键，并带上事件 id 作为确认标识")
    void relay_sendsToEventExchangeWithRoutingKey() {
        PictureEvent event = PictureEvent.of(PictureEventType.UPLOADED, 1L);

        relay.relay(event);

        ArgumentCaptor<CorrelationData> correlationCaptor = ArgumentCaptor.forClass(CorrelationData.class);
        verify(rabbitTemplate).convertAndSend(
                eq(PictureMqConfig.EVENT_EXCHANGE),
                eq("picture.uploaded"),
                eq(event),
                correlationCaptor.capture());
        assertEquals(event.eventId(), correlationCaptor.getValue().getId());
    }

    @Test
    @DisplayName("转发：机审完成事件使用 picture.moderated 路由键")
    void relay_usesModeratedRoutingKey() {
        PictureEvent event = PictureEvent.of(PictureEventType.MODERATED, 2L);

        relay.relay(event);

        verify(rabbitTemplate).convertAndSend(
                eq(PictureMqConfig.EVENT_EXCHANGE),
                eq("picture.moderated"),
                eq(event),
                any(CorrelationData.class));
    }

    @Test
    @DisplayName("降级：RabbitMQ 不可用时只记录日志，不向业务抛异常")
    void relay_swallowsBrokerError() {
        PictureEvent event = PictureEvent.of(PictureEventType.CHANGED, 3L);
        doThrow(new RuntimeException("broker down")).when(rabbitTemplate)
                .convertAndSend(anyString(), anyString(), any(Object.class), any(CorrelationData.class));

        assertDoesNotThrow(() -> relay.relay(event));
    }

    @Test
    @DisplayName("消息体：只包含 eventId、type、pictureId、occurredAt 四个字段")
    void messageBody_containsOnlyIdentifiers() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        Jackson2JsonMessageConverter converter = new Jackson2JsonMessageConverter(objectMapper);
        PictureEvent event = PictureEvent.of(PictureEventType.MODERATED, 123L);

        Message message = converter.toMessage(event, new MessageProperties());

        JsonNode body = objectMapper.readTree(message.getBody());
        Set<String> fieldNames = new HashSet<>();
        body.fieldNames().forEachRemaining(fieldNames::add);
        assertEquals(Set.of("eventId", "type", "pictureId", "occurredAt"), fieldNames);
        assertEquals(event.eventId(), body.get("eventId").asText());
        assertEquals("MODERATED", body.get("type").asText());
        assertFalse(body.has("name"), "消息体不能带业务字段");
    }
}

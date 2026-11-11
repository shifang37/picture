package com.tzy.picturebackend.event;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * PictureEventPublisher 单元测试：只验证 Spring 应用事件的类型和标识，不涉及 RabbitMQ
 */
class PictureEventPublisherTest {

    private ApplicationEventPublisher delegate;

    private PictureEventPublisher publisher;

    @BeforeEach
    void setUp() {
        delegate = mock(ApplicationEventPublisher.class);
        publisher = new PictureEventPublisher(delegate);
    }

    @Test
    @DisplayName("上传事件：类型、图片 id 正确，事件 id 与发生时间自动生成")
    void pictureUploaded_publishesUploadedEvent() {
        publisher.pictureUploaded(1L);

        PictureEvent event = captureEvents(1).get(0);
        assertEquals(PictureEventType.UPLOADED, event.type());
        assertEquals(Long.valueOf(1L), event.pictureId());
        assertNotNull(event.eventId());
        assertFalse(event.eventId().isBlank());
        assertNotNull(event.occurredAt());
    }

    @Test
    @DisplayName("机审与变更事件：分别使用对应的类型")
    void moderatedAndChanged_useExpectedTypes() {
        publisher.pictureModerated(2L);
        publisher.pictureChanged(3L);

        var events = captureEvents(2);
        assertEquals(PictureEventType.MODERATED, events.get(0).type());
        assertEquals(Long.valueOf(2L), events.get(0).pictureId());
        assertEquals(PictureEventType.CHANGED, events.get(1).type());
        assertEquals(Long.valueOf(3L), events.get(1).pictureId());
    }

    @Test
    @DisplayName("同一图片的两次事件拥有不同的事件 id，便于区分重复投递")
    void eachEventHasUniqueId() {
        publisher.pictureUploaded(1L);
        publisher.pictureUploaded(1L);

        var events = captureEvents(2);
        assertNotEquals(events.get(0).eventId(), events.get(1).eventId());
    }

    private java.util.List<PictureEvent> captureEvents(int count) {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(delegate, times(count)).publishEvent(captor.capture());
        return captor.getAllValues().stream().map(PictureEvent.class::cast).toList();
    }
}

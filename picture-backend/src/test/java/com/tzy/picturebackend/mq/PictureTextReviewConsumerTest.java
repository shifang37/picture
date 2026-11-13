package com.tzy.picturebackend.mq;

import com.tzy.picturebackend.config.TextReviewProperties;
import com.tzy.picturebackend.event.PictureEvent;
import com.tzy.picturebackend.event.PictureEventType;
import com.tzy.picturebackend.model.entity.Picture;
import com.tzy.picturebackend.model.enums.TextReviewStatusEnum;
import com.tzy.picturebackend.service.PictureService;
import com.tzy.picturebackend.service.TextReviewService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 文审消费者的幂等与状态判断：图片不存在、已审、开关关闭时不能抛异常（否则会重试进死信）
 */
class PictureTextReviewConsumerTest {

    private PictureService pictureService;

    private TextReviewService textReviewService;

    private TextReviewProperties textReviewProperties;

    private PictureTextReviewConsumer consumer;

    @BeforeEach
    void setUp() {
        pictureService = mock(PictureService.class);
        textReviewService = mock(TextReviewService.class);
        textReviewProperties = new TextReviewProperties();
        textReviewProperties.setEnabled(true);
        consumer = new PictureTextReviewConsumer(pictureService, textReviewService, textReviewProperties);
    }

    @Test
    @DisplayName("文审关闭：不查库、不调用审核")
    void handle_disabledSkipsWithoutQuery() {
        textReviewProperties.setEnabled(false);

        assertFalse(consumer.handle(PictureEvent.of(PictureEventType.UPLOADED, 1L)));

        verifyNoInteractions(pictureService, textReviewService);
    }

    @Test
    @DisplayName("空事件：直接确认，不查数据库")
    void handle_invalidEventIsAcknowledged() {
        assertFalse(consumer.handle(null));

        verifyNoInteractions(pictureService, textReviewService);
    }

    @Test
    @DisplayName("图片不存在：确认消息并丢弃，不抛异常")
    void handle_missingPictureIsAcknowledged() {
        when(pictureService.getById(404L)).thenReturn(null);

        assertFalse(consumer.handle(PictureEvent.of(PictureEventType.UPLOADED, 404L)));

        verifyNoInteractions(textReviewService);
    }

    @Test
    @DisplayName("已完成文审的图片跳过，避免重复调用接口与重复处置")
    void handle_reviewedPictureIsSkipped() {
        Picture picture = picture(1L);
        picture.setTextReviewStatus(TextReviewStatusEnum.NORMAL.getValue());
        when(pictureService.getById(1L)).thenReturn(picture);

        assertFalse(consumer.handle(PictureEvent.of(PictureEventType.UPLOADED, 1L)));

        verifyNoInteractions(textReviewService);
    }

    @Test
    @DisplayName("未文审的图片交给文审服务处理（编辑事件同样触发）")
    void handle_pendingPictureIsReviewed() {
        Picture picture = picture(1L);
        when(pictureService.getById(1L)).thenReturn(picture);

        assertTrue(consumer.handle(PictureEvent.of(PictureEventType.CHANGED, 1L)));

        verify(textReviewService).review(picture);
    }

    @Test
    @DisplayName("批量事件：逐张处理，已审与不存在的图片跳过")
    void handle_batchEventProcessesEachPicture() {
        Picture reviewed = picture(1L);
        reviewed.setTextReviewStatus(TextReviewStatusEnum.VIOLATION.getValue());
        Picture pending = picture(2L);
        when(pictureService.getById(1L)).thenReturn(reviewed);
        when(pictureService.getById(2L)).thenReturn(pending);
        when(pictureService.getById(3L)).thenReturn(null);

        assertTrue(consumer.handle(PictureEvent.of(PictureEventType.BATCH_CHANGED,
                List.of(1L, 2L, 3L))));

        verify(textReviewService).review(pending);
        verify(textReviewService, never()).review(reviewed);
    }

    @Test
    @DisplayName("审核失败向上抛出，交给 RabbitMQ 重试")
    void handle_propagatesFailure() {
        Picture picture = picture(1L);
        when(pictureService.getById(1L)).thenReturn(picture);
        doThrow(new IllegalStateException("api down")).when(textReviewService).review(any());

        assertThrows(IllegalStateException.class,
                () -> consumer.handle(PictureEvent.of(PictureEventType.UPLOADED, 1L)));
    }

    private Picture picture(Long id) {
        Picture picture = new Picture();
        picture.setId(id);
        picture.setName("名称");
        picture.setIntroduction("简介");
        return picture;
    }
}

package com.tzy.picturebackend.mq;

import com.tzy.picturebackend.event.PictureEvent;
import com.tzy.picturebackend.event.PictureEventType;
import com.tzy.picturebackend.model.entity.Picture;
import com.tzy.picturebackend.model.enums.PictureReviewStatusEnum;
import com.tzy.picturebackend.service.PictureService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 三个队列消费者的幂等与状态判断：图片不存在、已处理、已删除时不能抛异常（否则会重试进死信）
 */
class PictureEventConsumerTest {

    private PictureService pictureService;

    private PictureModerationConsumer moderationConsumer;

    private PictureAiConsumer aiConsumer;

    private PictureSearchConsumer searchConsumer;

    @BeforeEach
    void setUp() {
        pictureService = mock(PictureService.class);
        moderationConsumer = new PictureModerationConsumer(pictureService);
        aiConsumer = new PictureAiConsumer(pictureService);
        searchConsumer = new PictureSearchConsumer(pictureService);
    }

    @Test
    @DisplayName("机审：图片不存在时确认消息并丢弃，不抛异常")
    void moderation_missingPictureIsAcknowledged() {
        when(pictureService.getById(404L)).thenReturn(null);

        assertFalse(moderationConsumer.handle(PictureEvent.of(PictureEventType.UPLOADED, 404L)));
        verify(pictureService).getById(404L);
    }

    @Test
    @DisplayName("机审：空事件直接确认，不查数据库")
    void moderation_invalidEventIsAcknowledged() {
        assertFalse(moderationConsumer.handle(null));
        verifyNoInteractions(pictureService);
    }

    @Test
    @DisplayName("机审：仍待审核的图片需要处理")
    void moderation_pendingPictureIsHandled() {
        when(pictureService.getById(1L))
                .thenReturn(picture(1L, PictureReviewStatusEnum.REVIEWING.getValue()));

        assertTrue(moderationConsumer.handle(PictureEvent.of(PictureEventType.UPLOADED, 1L)));
    }

    @Test
    @DisplayName("机审：管理员已提前审核的图片直接跳过，避免覆盖人工结论")
    void moderation_reviewedPictureIsSkipped() {
        when(pictureService.getById(1L))
                .thenReturn(picture(1L, PictureReviewStatusEnum.PASS.getValue()));

        assertFalse(moderationConsumer.handle(PictureEvent.of(PictureEventType.UPLOADED, 1L)));
    }

    @Test
    @DisplayName("AI：图片不存在时确认并丢弃")
    void ai_missingPictureIsAcknowledged() {
        when(pictureService.getById(404L)).thenReturn(null);

        assertFalse(aiConsumer.handle(PictureEvent.of(PictureEventType.MODERATED, 404L)));
    }

    @Test
    @DisplayName("AI：图片存在时进入生成建议流程")
    void ai_existingPictureIsHandled() {
        when(pictureService.getById(2L))
                .thenReturn(picture(2L, PictureReviewStatusEnum.PASS.getValue()));

        assertTrue(aiConsumer.handle(PictureEvent.of(PictureEventType.MODERATED, 2L)));
    }

    @Test
    @DisplayName("搜索：图片存在时同步索引")
    void search_existingPictureIsHandled() {
        when(pictureService.getById(3L))
                .thenReturn(picture(3L, PictureReviewStatusEnum.PASS.getValue()));

        assertTrue(searchConsumer.handle(PictureEvent.of(PictureEventType.CHANGED, 3L)));
    }

    @Test
    @DisplayName("搜索：图片已删除时仍要按 id 删除索引，不能当成可忽略的消息")
    void search_deletedPictureIsStillHandled() {
        when(pictureService.getById(4L)).thenReturn(null);

        assertTrue(searchConsumer.handle(PictureEvent.of(PictureEventType.CHANGED, 4L)));
    }

    private Picture picture(Long id, int reviewStatus) {
        Picture picture = new Picture();
        picture.setId(id);
        picture.setReviewStatus(reviewStatus);
        return picture;
    }
}

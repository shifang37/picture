package com.tzy.picturebackend.mq;

import com.tzy.picturebackend.config.AiProperties;
import com.tzy.picturebackend.config.MachineReviewProperties;
import com.tzy.picturebackend.event.PictureEvent;
import com.tzy.picturebackend.event.PictureEventType;
import com.tzy.picturebackend.manager.MachineReviewException;
import com.tzy.picturebackend.manager.PictureListCache;
import com.tzy.picturebackend.model.entity.Picture;
import com.tzy.picturebackend.model.enums.MachineReviewStatusEnum;
import com.tzy.picturebackend.model.enums.PictureReviewStatusEnum;
import com.tzy.picturebackend.service.AiSuggestionService;
import com.tzy.picturebackend.service.MachineReviewService;
import com.tzy.picturebackend.service.PictureSearchService;
import com.tzy.picturebackend.service.PictureService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
 * 队列消费者的幂等与状态判断：图片不存在、已处理、开关关闭时不能抛异常（否则会重试进死信）
 */
class PictureEventConsumerTest {

    private PictureService pictureService;

    private MachineReviewService machineReviewService;

    private AiSuggestionService aiSuggestionService;

    private MachineReviewProperties machineReviewProperties;

    private AiProperties aiProperties;

    private PictureSearchService pictureSearchService;

    private PictureListCache pictureListCache;

    private PictureModerationConsumer moderationConsumer;

    private PictureAiConsumer aiConsumer;

    private PictureSearchConsumer searchConsumer;

    @BeforeEach
    void setUp() {
        pictureService = mock(PictureService.class);
        machineReviewService = mock(MachineReviewService.class);
        aiSuggestionService = mock(AiSuggestionService.class);
        machineReviewProperties = new MachineReviewProperties();
        machineReviewProperties.setEnabled(true);
        aiProperties = new AiProperties();
        aiProperties.setEnabled(true);
        pictureSearchService = mock(PictureSearchService.class);
        pictureListCache = mock(PictureListCache.class);
        moderationConsumer = new PictureModerationConsumer(
                pictureService, machineReviewService, machineReviewProperties);
        aiConsumer = new PictureAiConsumer(
                pictureService, aiSuggestionService, aiProperties, machineReviewProperties);
        searchConsumer = new PictureSearchConsumer(pictureService, pictureSearchService, pictureListCache);
    }

    // ==================== 机审 ====================

    @Test
    @DisplayName("机审：图片不存在时确认消息并丢弃，不抛异常")
    void moderation_missingPictureIsAcknowledged() {
        when(pictureService.getById(404L)).thenReturn(null);

        assertFalse(moderationConsumer.handle(PictureEvent.of(PictureEventType.UPLOADED, 404L)));
        verify(pictureService).getById(404L);
        verifyNoInteractions(machineReviewService);
    }

    @Test
    @DisplayName("机审：空事件直接确认，不查数据库")
    void moderation_invalidEventIsAcknowledged() {
        assertFalse(moderationConsumer.handle(null));
        verifyNoInteractions(pictureService, machineReviewService);
    }

    @Test
    @DisplayName("机审：开关关闭时不查库、不调用审核")
    void moderation_disabledSkipsWithoutQuery() {
        machineReviewProperties.setEnabled(false);

        assertFalse(moderationConsumer.handle(PictureEvent.of(PictureEventType.UPLOADED, 1L)));
        verifyNoInteractions(pictureService, machineReviewService);
    }

    @Test
    @DisplayName("机审：仍待审核的图片交给机审服务处理")
    void moderation_pendingPictureIsHandled() {
        Picture picture = picture(1L, PictureReviewStatusEnum.REVIEWING.getValue());
        when(pictureService.getById(1L)).thenReturn(picture);

        assertTrue(moderationConsumer.handle(PictureEvent.of(PictureEventType.UPLOADED, 1L)));

        verify(machineReviewService).review(picture);
    }

    @Test
    @DisplayName("机审：管理员已提前审核的图片直接跳过，避免覆盖人工结论")
    void moderation_reviewedPictureIsSkipped() {
        when(pictureService.getById(1L))
                .thenReturn(picture(1L, PictureReviewStatusEnum.PASS.getValue()));

        assertFalse(moderationConsumer.handle(PictureEvent.of(PictureEventType.UPLOADED, 1L)));
        verifyNoInteractions(machineReviewService);
    }

    @Test
    @DisplayName("机审：机审字段已有值的重复消息直接确认")
    void moderation_machineReviewedPictureIsSkipped() {
        Picture picture = picture(1L, PictureReviewStatusEnum.REVIEWING.getValue());
        picture.setMachineReviewStatus(0);
        when(pictureService.getById(1L)).thenReturn(picture);

        assertFalse(moderationConsumer.handle(PictureEvent.of(PictureEventType.UPLOADED, 1L)));
        verifyNoInteractions(machineReviewService);
    }

    @Test
    @DisplayName("机审：审核失败向上抛出，交给 RabbitMQ 重试与死信")
    void moderation_failureIsPropagated() {
        Picture picture = picture(1L, PictureReviewStatusEnum.REVIEWING.getValue());
        when(pictureService.getById(1L)).thenReturn(picture);
        doThrow(new MachineReviewException("api down")).when(machineReviewService).review(any());

        assertThrows(MachineReviewException.class,
                () -> moderationConsumer.handle(PictureEvent.of(PictureEventType.UPLOADED, 1L)));
    }

    // ==================== AI 建议 ====================

    @Test
    @DisplayName("AI：开关关闭时不查库、不调用模型")
    void ai_disabledSkipsWithoutQuery() {
        aiProperties.setEnabled(false);

        assertFalse(aiConsumer.handle(PictureEvent.of(PictureEventType.UPLOADED, 1L)));
        verifyNoInteractions(pictureService, aiSuggestionService);
    }

    @Test
    @DisplayName("AI：图片不存在时确认并丢弃")
    void ai_missingPictureIsAcknowledged() {
        when(pictureService.getById(404L)).thenReturn(null);

        assertFalse(aiConsumer.handle(PictureEvent.of(PictureEventType.MODERATED, 404L)));
        verifyNoInteractions(aiSuggestionService);
    }

    @Test
    @DisplayName("AI：机审正常后生成建议")
    void ai_moderatedNormalPictureIsHandled() {
        Picture picture = picture(2L, PictureReviewStatusEnum.PASS.getValue());
        picture.setMachineReviewStatus(MachineReviewStatusEnum.NORMAL.getValue());
        when(pictureService.getById(2L)).thenReturn(picture);

        assertTrue(aiConsumer.handle(PictureEvent.of(PictureEventType.MODERATED, 2L)));

        verify(aiSuggestionService).generate(picture);
    }

    @Test
    @DisplayName("AI：机审疑似/违规时不把图片发给第三方模型")
    void ai_moderatedSuspectPictureIsSkipped() {
        Picture picture = picture(2L, PictureReviewStatusEnum.REVIEWING.getValue());
        picture.setMachineReviewStatus(MachineReviewStatusEnum.SUSPECT.getValue());
        when(pictureService.getById(2L)).thenReturn(picture);

        assertFalse(aiConsumer.handle(PictureEvent.of(PictureEventType.MODERATED, 2L)));
        verifyNoInteractions(aiSuggestionService);
    }

    @Test
    @DisplayName("AI：机审开启时普通待审核图片等待 moderated 事件")
    void ai_waitsForModeratedWhenMachineEnabled() {
        when(pictureService.getById(3L))
                .thenReturn(picture(3L, PictureReviewStatusEnum.REVIEWING.getValue()));

        assertFalse(aiConsumer.handle(PictureEvent.of(PictureEventType.UPLOADED, 3L)));
        verifyNoInteractions(aiSuggestionService);
    }

    @Test
    @DisplayName("AI：管理员直传已通过的图片无需等机审，上传事件直接生成")
    void ai_processesAdminUploadedPicture() {
        Picture picture = picture(4L, PictureReviewStatusEnum.PASS.getValue());
        when(pictureService.getById(4L)).thenReturn(picture);

        assertTrue(aiConsumer.handle(PictureEvent.of(PictureEventType.UPLOADED, 4L)));

        verify(aiSuggestionService).generate(picture);
    }

    @Test
    @DisplayName("AI：机审关闭时上传事件直接生成建议")
    void ai_processesUploadedWhenMachineDisabled() {
        machineReviewProperties.setEnabled(false);
        Picture picture = picture(5L, PictureReviewStatusEnum.REVIEWING.getValue());
        when(pictureService.getById(5L)).thenReturn(picture);

        assertTrue(aiConsumer.handle(PictureEvent.of(PictureEventType.UPLOADED, 5L)));

        verify(aiSuggestionService).generate(picture);
    }

    @Test
    @DisplayName("AI：已拒绝图片不生成建议")
    void ai_rejectedPictureIsSkipped() {
        machineReviewProperties.setEnabled(false);
        when(pictureService.getById(6L))
                .thenReturn(picture(6L, PictureReviewStatusEnum.REJECT.getValue()));

        assertFalse(aiConsumer.handle(PictureEvent.of(PictureEventType.UPLOADED, 6L)));
        verifyNoInteractions(aiSuggestionService);
    }

    @Test
    @DisplayName("AI：生成失败向上抛出，交给 RabbitMQ 重试")
    void ai_failureIsPropagated() {
        Picture picture = picture(7L, PictureReviewStatusEnum.PASS.getValue());
        when(pictureService.getById(7L)).thenReturn(picture);
        doThrow(new RuntimeException("db down")).when(aiSuggestionService).generate(any());

        assertThrows(RuntimeException.class,
                () -> aiConsumer.handle(PictureEvent.of(PictureEventType.UPLOADED, 7L)));
    }

    // ==================== 搜索 ====================

    @Test
    @DisplayName("搜索：已通过的图片写入索引，并在写完后失效列表缓存")
    void search_existingPictureIsHandled() {
        Picture picture = picture(3L, PictureReviewStatusEnum.PASS.getValue());
        when(pictureService.getById(3L)).thenReturn(picture);
        when(pictureSearchService.enabled()).thenReturn(true);

        assertTrue(searchConsumer.handle(PictureEvent.of(PictureEventType.CHANGED, 3L)));

        verify(pictureSearchService).indexPicture(picture);
        verify(pictureSearchService, never()).deletePicture(3L);
        verify(pictureListCache).invalidate();
    }

    @Test
    @DisplayName("搜索：图片已删除时仍要按 id 删除索引，不能当成可忽略的消息")
    void search_deletedPictureIsStillHandled() {
        when(pictureService.getById(4L)).thenReturn(null);

        assertTrue(searchConsumer.handle(PictureEvent.of(PictureEventType.CHANGED, 4L)));

        verify(pictureSearchService).deletePicture(4L);
        verify(pictureSearchService, never()).indexPicture(any());
    }

    @Test
    @DisplayName("搜索：待审核 / 已拒绝的图片从索引删除，避免未过审内容被搜到")
    void search_unreviewedPictureIsRemovedFromIndex() {
        when(pictureService.getById(5L))
                .thenReturn(picture(5L, PictureReviewStatusEnum.REVIEWING.getValue()));
        when(pictureService.getById(6L))
                .thenReturn(picture(6L, PictureReviewStatusEnum.REJECT.getValue()));

        assertTrue(searchConsumer.handle(PictureEvent.of(PictureEventType.CHANGED, 5L)));
        assertTrue(searchConsumer.handle(PictureEvent.of(PictureEventType.CHANGED, 6L)));

        verify(pictureSearchService).deletePicture(5L);
        verify(pictureSearchService).deletePicture(6L);
        verify(pictureSearchService, never()).indexPicture(any());
    }

    @Test
    @DisplayName("搜索：空事件直接确认，不查库也不碰索引")
    void search_invalidEventIsAcknowledged() {
        assertFalse(searchConsumer.handle(null));

        verifyNoInteractions(pictureService, pictureSearchService);
    }

    private Picture picture(Long id, int reviewStatus) {
        Picture picture = new Picture();
        picture.setId(id);
        picture.setReviewStatus(reviewStatus);
        return picture;
    }
}

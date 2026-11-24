package com.tzy.picturebackend.mq;

import com.tzy.picturebackend.event.PictureEvent;
import com.tzy.picturebackend.event.PictureEventType;
import com.tzy.picturebackend.model.dto.rag.RagDuplicateResult;
import com.tzy.picturebackend.model.entity.Picture;
import com.tzy.picturebackend.model.enums.PictureReviewStatusEnum;
import com.tzy.picturebackend.service.PictureService;
import com.tzy.picturebackend.service.RagDuplicateService;
import com.tzy.picturebackend.service.RagIndexService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * RAG 消费者：公开图写索引、非公开/已删除删索引，单条失败不影响整批；
 * 索引成功后再做近重复检测，检测失败不能反过来影响索引结果。
 */
class PictureRagConsumerTest {

    private PictureService pictureService;

    private RagIndexService ragIndexService;

    private RagDuplicateService ragDuplicateService;

    private PictureRagConsumer consumer;

    @BeforeEach
    void setUp() {
        pictureService = mock(PictureService.class);
        ragIndexService = mock(RagIndexService.class);
        ragDuplicateService = mock(RagDuplicateService.class);
        consumer = new PictureRagConsumer(pictureService, ragIndexService, ragDuplicateService);
    }

    @Test
    @DisplayName("未启用 RAG：不查库、不调向量服务")
    void handle_disabledSkipsEverything() {
        when(ragIndexService.enabled()).thenReturn(false);

        assertFalse(consumer.handle(PictureEvent.of(PictureEventType.CHANGED, 1L)));

        verifyNoInteractions(pictureService);
    }

    @Test
    @DisplayName("空事件：直接确认")
    void handle_nullEventReturnsFalse() {
        when(ragIndexService.enabled()).thenReturn(true);

        assertFalse(consumer.handle(null));

        verifyNoInteractions(pictureService);
    }

    @Test
    @DisplayName("公开图写索引，查不到的图片按删除处理")
    void handle_indexesPassedAndDeletesMissing() {
        when(ragIndexService.enabled()).thenReturn(true);
        Picture passed = picture(1L, PictureReviewStatusEnum.PASS.getValue());
        when(pictureService.listByIds(anyCollection())).thenReturn(List.of(passed));

        assertTrue(consumer.handle(PictureEvent.of(PictureEventType.BATCH_CHANGED, List.of(1L, 2L))));

        verify(ragIndexService).indexPicture(passed);
        verify(ragIndexService).deletePicture(2L);
    }

    @Test
    @DisplayName("待审核/已拒绝：只删索引")
    void handle_deletesNotPassed() {
        when(ragIndexService.enabled()).thenReturn(true);
        Picture reviewing = picture(1L, PictureReviewStatusEnum.REVIEWING.getValue());
        Picture rejected = picture(2L, PictureReviewStatusEnum.REJECT.getValue());
        when(pictureService.listByIds(anyCollection())).thenReturn(List.of(reviewing, rejected));

        assertTrue(consumer.handle(PictureEvent.of(PictureEventType.CHANGED, List.of(1L, 2L))));

        verify(ragIndexService).deletePicture(1L);
        verify(ragIndexService).deletePicture(2L);
        verify(ragIndexService, never()).indexPicture(reviewing);
        verify(ragIndexService, never()).indexPicture(rejected);
        verifyNoInteractions(ragDuplicateService);
    }

    @Test
    @DisplayName("公开图写索引成功后才做近重复检测")
    void handle_checksDuplicateAfterIndex() {
        when(ragIndexService.enabled()).thenReturn(true);
        when(ragDuplicateService.enabled()).thenReturn(true);
        Picture passed = picture(1L, PictureReviewStatusEnum.PASS.getValue());
        when(pictureService.listByIds(anyCollection())).thenReturn(List.of(passed));
        when(ragDuplicateService.check(passed)).thenReturn(RagDuplicateResult.hit(9L, 0.99, true));

        assertTrue(consumer.handle(PictureEvent.of(PictureEventType.CHANGED, 1L)));

        verify(ragIndexService).indexPicture(passed);
        verify(ragDuplicateService).check(passed);
    }

    @Test
    @DisplayName("近重复检测异常不打断整批，也不把索引记成失败")
    void handle_duplicateFailureDoesNotInterruptBatch() {
        when(ragIndexService.enabled()).thenReturn(true);
        when(ragDuplicateService.enabled()).thenReturn(true);
        Picture first = picture(1L, PictureReviewStatusEnum.PASS.getValue());
        Picture second = picture(2L, PictureReviewStatusEnum.PASS.getValue());
        when(pictureService.listByIds(anyCollection())).thenReturn(List.of(first, second));
        when(ragDuplicateService.check(first)).thenThrow(new IllegalStateException("es down"));
        when(ragDuplicateService.check(second)).thenReturn(RagDuplicateResult.miss());

        assertTrue(consumer.handle(PictureEvent.of(PictureEventType.BATCH_CHANGED, List.of(1L, 2L))));

        verify(ragIndexService).indexPicture(first);
        verify(ragIndexService).indexPicture(second);
        verify(ragDuplicateService).check(second);
    }

    @Test
    @DisplayName("单条失败：记录后继续处理同批其他图片")
    void handle_continuesAfterSingleFailure() {
        when(ragIndexService.enabled()).thenReturn(true);
        Picture first = picture(1L, PictureReviewStatusEnum.PASS.getValue());
        Picture second = picture(2L, PictureReviewStatusEnum.PASS.getValue());
        when(pictureService.listByIds(anyCollection())).thenReturn(List.of(first, second));
        doThrow(new IllegalStateException("embedding down"))
                .when(ragIndexService).indexPicture(first);

        assertTrue(consumer.handle(PictureEvent.of(PictureEventType.BATCH_CHANGED, List.of(1L, 2L))));

        verify(ragIndexService).indexPicture(first);
        verify(ragIndexService).indexPicture(second);
    }

    private Picture picture(Long id, int reviewStatus) {
        Picture picture = new Picture();
        picture.setId(id);
        picture.setReviewStatus(reviewStatus);
        return picture;
    }
}

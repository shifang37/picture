package com.tzy.picturebackend.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.tzy.picturebackend.config.TextReviewProperties;
import com.tzy.picturebackend.event.NotificationEventPublisher;
import com.tzy.picturebackend.event.PictureEventPublisher;
import com.tzy.picturebackend.manager.PictureListCache;
import com.tzy.picturebackend.manager.TextReviewException;
import com.tzy.picturebackend.manager.TextReviewManager;
import com.tzy.picturebackend.manager.TextReviewResult;
import com.tzy.picturebackend.model.entity.Picture;
import com.tzy.picturebackend.model.enums.PictureReviewStatusEnum;
import com.tzy.picturebackend.model.enums.TextReviewModeEnum;
import com.tzy.picturebackend.model.enums.TextReviewStatusEnum;
import com.tzy.picturebackend.service.PictureReviewLogService;
import com.tzy.picturebackend.service.PictureService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 文本审核落库与模式处置：幂等、assist 只记录、auto-reject 自动拒绝、人工结论优先
 */
class TextReviewServiceImplTest {

    private TextReviewManager textReviewManager;

    private TextReviewProperties textReviewProperties;

    private PictureService pictureService;

    private PictureReviewLogService pictureReviewLogService;

    private PictureListCache pictureListCache;

    private PictureEventPublisher pictureEventPublisher;

    private NotificationEventPublisher notificationEventPublisher;

    private TextReviewServiceImpl textReviewService;

    @BeforeEach
    void setUp() {
        textReviewManager = mock(TextReviewManager.class);
        textReviewProperties = new TextReviewProperties();
        textReviewProperties.setEnabled(true);
        textReviewProperties.setMode(TextReviewModeEnum.ASSIST);
        pictureService = mock(PictureService.class);
        pictureReviewLogService = mock(PictureReviewLogService.class);
        pictureListCache = mock(PictureListCache.class);
        pictureEventPublisher = mock(PictureEventPublisher.class);
        notificationEventPublisher = mock(NotificationEventPublisher.class);
        textReviewService = new TextReviewServiceImpl(textReviewManager, textReviewProperties,
                pictureService, pictureReviewLogService, pictureListCache,
                pictureEventPublisher, notificationEventPublisher);
    }

    @Test
    @DisplayName("关闭文审：不调用接口、不写库")
    void review_disabledSkipsEverything() {
        textReviewProperties.setEnabled(false);

        textReviewService.review(picture("名称", "简介"));

        verifyNoInteractions(textReviewManager, pictureService, pictureReviewLogService,
                pictureListCache, pictureEventPublisher, notificationEventPublisher);
    }

    @Test
    @DisplayName("已完成文审的图片直接跳过，不重复调用接口")
    void review_alreadyReviewedSkips() {
        Picture picture = picture("名称", "简介");
        picture.setTextReviewStatus(TextReviewStatusEnum.NORMAL.getValue());

        textReviewService.review(picture);

        verifyNoInteractions(textReviewManager, pictureService);
    }

    @Test
    @DisplayName("名称与简介都为空：不发起接口调用，也不产生费用")
    void review_blankTextSkips() {
        textReviewService.review(picture("  ", null));

        verifyNoInteractions(textReviewManager, pictureService);
    }

    @Test
    @DisplayName("assist：只写文审字段，不改审核状态")
    void review_assistWritesTextFieldsOnly() {
        Picture picture = picture("名称", "简介");
        when(textReviewManager.review("名称\n简介"))
                .thenReturn(new TextReviewResult(TextReviewStatusEnum.NORMAL, "Normal", 95));
        when(pictureService.update(any(Wrapper.class))).thenReturn(true);

        textReviewService.review(picture);

        UpdateWrapper<Picture> wrapper = capturedWrapper(1);
        assertTrue(wrapper.getSqlSet().contains("textReviewStatus"), wrapper.getSqlSet());
        assertTrue(wrapper.getSqlSet().contains("textReviewLabel"), wrapper.getSqlSet());
        assertTrue(wrapper.getSqlSet().contains("textReviewScore"), wrapper.getSqlSet());
        assertTrue(wrapper.getSqlSet().contains("textReviewTime"), wrapper.getSqlSet());
        assertTrue(wrapper.getSqlSegment().contains("textReviewStatus"),
                "文审写入必须带 textReviewStatus IS NULL 条件: " + wrapper.getSqlSegment());
        assertTrue(wrapper.getParamNameValuePairs()
                .containsValue(TextReviewStatusEnum.NORMAL.getValue()));
        assertTrue(wrapper.getParamNameValuePairs().containsValue("Normal"));
        assertTrue(wrapper.getParamNameValuePairs().containsValue(95));
        verifyNoInteractions(pictureReviewLogService, pictureListCache,
                pictureEventPublisher, notificationEventPublisher);
    }

    @Test
    @DisplayName("幂等：文审字段已被写入时不再处置")
    void review_duplicateResultSkips() {
        Picture picture = picture("名称", "简介");
        when(textReviewManager.review(any())).thenReturn(
                new TextReviewResult(TextReviewStatusEnum.VIOLATION, "Porn", 90));
        when(pictureService.update(any(Wrapper.class))).thenReturn(false);

        textReviewService.review(picture);

        verify(pictureService, times(1)).update(any(Wrapper.class));
        verifyNoInteractions(pictureReviewLogService, pictureListCache,
                pictureEventPublisher, notificationEventPublisher);
    }

    @Test
    @DisplayName("auto-reject：违规文本自动拒绝并写系统审核历史")
    void review_autoRejectViolation() {
        textReviewProperties.setMode(TextReviewModeEnum.AUTO_REJECT);
        Picture picture = picture("名称", "简介");
        when(textReviewManager.review(any()))
                .thenReturn(new TextReviewResult(TextReviewStatusEnum.VIOLATION, "Porn", 90));
        when(pictureService.update(any(Wrapper.class))).thenReturn(true, true);

        textReviewService.review(picture);

        UpdateWrapper<Picture> reviewWrapper = capturedWrapper(2);
        assertTrue(reviewWrapper.getSqlSet().contains("reviewStatus"), reviewWrapper.getSqlSet());
        assertTrue(reviewWrapper.getSqlSegment().contains("reviewStatus"),
                "自动处置必须带 reviewStatus = 待审核 条件: " + reviewWrapper.getSqlSegment());
        assertTrue(reviewWrapper.getParamNameValuePairs()
                .containsValue(PictureReviewStatusEnum.REJECT.getValue()));
        assertTrue(reviewWrapper.getParamNameValuePairs().containsValue(0L));
        String message = "文审违规（Porn，置信度 90），自动拒绝";
        verify(pictureReviewLogService).record(1L, PictureReviewLogService.SYSTEM_REVIEWER_ID,
                PictureReviewStatusEnum.REJECT.getValue(), message);
        verify(notificationEventPublisher).publish(any());
        verify(pictureListCache).invalidate();
        verify(pictureEventPublisher).pictureChanged(1L);
    }

    @Test
    @DisplayName("auto-reject：正常与疑似文本仍走人工")
    void review_autoRejectLeavesNormalAndSuspectForHuman() {
        textReviewProperties.setMode(TextReviewModeEnum.AUTO_REJECT);
        when(pictureService.update(any(Wrapper.class))).thenReturn(true);

        when(textReviewManager.review(any()))
                .thenReturn(new TextReviewResult(TextReviewStatusEnum.NORMAL, "Normal", 99));
        textReviewService.review(picture("名称", "简介"));

        when(textReviewManager.review(any()))
                .thenReturn(new TextReviewResult(TextReviewStatusEnum.SUSPECT, "Ads", 60));
        textReviewService.review(picture("名称", "简介"));

        verify(pictureService, times(2)).update(any(Wrapper.class));
        verifyNoInteractions(pictureReviewLogService, pictureListCache,
                pictureEventPublisher, notificationEventPublisher);
    }

    @Test
    @DisplayName("并发：人工审核先完成时只保留文审字段，不覆盖人工结论")
    void review_humanReviewWinsRace() {
        textReviewProperties.setMode(TextReviewModeEnum.AUTO_REJECT);
        when(textReviewManager.review(any()))
                .thenReturn(new TextReviewResult(TextReviewStatusEnum.VIOLATION, "Porn", 90));
        when(pictureService.update(any(Wrapper.class))).thenReturn(true, false);

        textReviewService.review(picture("名称", "简介"));

        verify(pictureReviewLogService, never()).record(any(), any(), any(), any());
        verify(pictureListCache, never()).invalidate();
        verify(pictureEventPublisher, never()).pictureChanged(any());
    }

    @Test
    @DisplayName("审核接口失败时向上抛出，交给消息重试")
    void review_propagatesManagerFailure() {
        when(textReviewManager.review(any())).thenThrow(new TextReviewException("api down"));

        assertThrows(TextReviewException.class,
                () -> textReviewService.review(picture("名称", "简介")));
        verifyNoInteractions(pictureService, pictureReviewLogService);
    }

    @Test
    @DisplayName("审核文本：名称在简介前，空值跳过，首尾空白裁剪，超长截断")
    void buildContent_joinsTrimsAndTruncates() {
        assertEquals("名称\n简介", TextReviewServiceImpl.buildContent(picture(" 名称 ", " 简介 "), 100));
        assertEquals("名称", TextReviewServiceImpl.buildContent(picture("名称", "  "), 100));
        assertEquals("", TextReviewServiceImpl.buildContent(picture(null, null), 100));
        assertEquals(5, TextReviewServiceImpl.buildContent(picture("12345", "67890"), 5).length());
    }

    @Test
    @DisplayName("自动处置意见：带标签与置信度，超长截断到 512")
    void buildAutoReviewMessage_formatsAndTruncates() {
        assertEquals("文审违规（Porn/Sexy，置信度 88），自动拒绝",
                TextReviewServiceImpl.buildAutoReviewMessage(
                        new TextReviewResult(TextReviewStatusEnum.VIOLATION, "Porn/Sexy", 88)));
        assertEquals(512, TextReviewServiceImpl.buildAutoReviewMessage(
                new TextReviewResult(TextReviewStatusEnum.VIOLATION, "a".repeat(600), 100)).length());
    }

    @SuppressWarnings("unchecked")
    private UpdateWrapper<Picture> capturedWrapper(int callIndex) {
        ArgumentCaptor<Wrapper<Picture>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(pictureService, times(callIndex)).update(captor.capture());
        return (UpdateWrapper<Picture>) captor.getAllValues().get(callIndex - 1);
    }

    private Picture picture(String name, String introduction) {
        Picture picture = new Picture();
        picture.setId(1L);
        picture.setUserId(100L);
        picture.setName(name);
        picture.setIntroduction(introduction);
        picture.setReviewStatus(PictureReviewStatusEnum.REVIEWING.getValue());
        return picture;
    }
}

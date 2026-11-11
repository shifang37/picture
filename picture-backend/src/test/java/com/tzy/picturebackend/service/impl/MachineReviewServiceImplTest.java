package com.tzy.picturebackend.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.tzy.picturebackend.config.MachineReviewProperties;
import com.tzy.picturebackend.event.PictureEventPublisher;
import com.tzy.picturebackend.manager.MachineReviewException;
import com.tzy.picturebackend.manager.MachineReviewManager;
import com.tzy.picturebackend.manager.MachineReviewResult;
import com.tzy.picturebackend.manager.PictureListCache;
import com.tzy.picturebackend.model.entity.Picture;
import com.tzy.picturebackend.model.enums.MachineReviewModeEnum;
import com.tzy.picturebackend.model.enums.MachineReviewStatusEnum;
import com.tzy.picturebackend.model.enums.PictureReviewStatusEnum;
import com.tzy.picturebackend.service.PictureReviewLogService;
import com.tzy.picturebackend.service.PictureService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
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
 * 机审落库与模式处置：幂等、assist 只记录、auto 自动处置、人工结论优先
 */
class MachineReviewServiceImplTest {

    private MachineReviewManager machineReviewManager;

    private MachineReviewProperties machineReviewProperties;

    private PictureService pictureService;

    private PictureReviewLogService pictureReviewLogService;

    private PictureListCache pictureListCache;

    private PictureEventPublisher pictureEventPublisher;

    private MachineReviewServiceImpl machineReviewService;

    @BeforeEach
    void setUp() {
        machineReviewManager = mock(MachineReviewManager.class);
        machineReviewProperties = new MachineReviewProperties();
        machineReviewProperties.setEnabled(true);
        machineReviewProperties.setMode(MachineReviewModeEnum.ASSIST);
        pictureService = mock(PictureService.class);
        pictureReviewLogService = mock(PictureReviewLogService.class);
        pictureListCache = mock(PictureListCache.class);
        pictureEventPublisher = mock(PictureEventPublisher.class);
        machineReviewService = new MachineReviewServiceImpl(machineReviewManager,
                machineReviewProperties, pictureService, pictureReviewLogService,
                pictureListCache, pictureEventPublisher);
    }

    @Test
    @DisplayName("关闭机审：不调用接口、不写库")
    void review_disabledSkipsEverything() {
        machineReviewProperties.setEnabled(false);

        machineReviewService.review(picture(PictureReviewStatusEnum.REVIEWING.getValue()));

        verifyNoInteractions(machineReviewManager, pictureService,
                pictureReviewLogService, pictureListCache, pictureEventPublisher);
    }

    @Test
    @DisplayName("assist：只写机审字段并发布 moderated，不改审核状态")
    void review_assistWritesMachineFieldsOnly() {
        Picture picture = picture(PictureReviewStatusEnum.REVIEWING.getValue());
        when(machineReviewManager.review(picture.getUrl()))
                .thenReturn(new MachineReviewResult(MachineReviewStatusEnum.NORMAL, "Normal", 95));
        when(pictureService.update(any(Wrapper.class))).thenReturn(true);

        machineReviewService.review(picture);

        UpdateWrapper<Picture> wrapper = capturedMachineWrapper(1);
        assertTrue(wrapper.getSqlSet().contains("machineReviewStatus"), wrapper.getSqlSet());
        assertTrue(wrapper.getSqlSet().contains("machineReviewLabel"), wrapper.getSqlSet());
        assertTrue(wrapper.getSqlSet().contains("machineReviewScore"), wrapper.getSqlSet());
        assertTrue(wrapper.getSqlSet().contains("machineReviewTime"), wrapper.getSqlSet());
        assertTrue(wrapper.getParamNameValuePairs().containsValue(
                MachineReviewStatusEnum.NORMAL.getValue()));
        assertTrue(wrapper.getParamNameValuePairs().containsValue("Normal"));
        assertTrue(wrapper.getParamNameValuePairs().containsValue(95));
        verify(pictureEventPublisher).pictureModerated(1L);
        verify(pictureEventPublisher, never()).pictureChanged(1L);
        verifyNoInteractions(pictureReviewLogService);
        verify(pictureListCache, never()).invalidate();
    }

    @Test
    @DisplayName("幂等：机审字段已被写入时不再发布事件")
    void review_duplicateMachineResultSkips() {
        Picture picture = picture(PictureReviewStatusEnum.REVIEWING.getValue());
        when(machineReviewManager.review(picture.getUrl()))
                .thenReturn(new MachineReviewResult(MachineReviewStatusEnum.NORMAL, "Normal", 95));
        when(pictureService.update(any(Wrapper.class))).thenReturn(false);

        machineReviewService.review(picture);

        verifyNoInteractions(pictureReviewLogService, pictureListCache);
        verify(pictureEventPublisher, never()).pictureModerated(1L);
        verify(pictureEventPublisher, never()).pictureChanged(1L);
    }

    @Test
    @DisplayName("auto-reject：违规自动拒绝并写系统机审历史")
    void review_autoRejectViolation() {
        machineReviewProperties.setMode(MachineReviewModeEnum.AUTO_REJECT);
        Picture picture = picture(PictureReviewStatusEnum.REVIEWING.getValue());
        when(machineReviewManager.review(picture.getUrl()))
                .thenReturn(new MachineReviewResult(MachineReviewStatusEnum.VIOLATION, "Porn", 90));
        when(pictureService.update(any(Wrapper.class))).thenReturn(true, true);

        machineReviewService.review(picture);

        List<Wrapper<Picture>> wrappers = capturedWrappers(2);
        UpdateWrapper<Picture> reviewWrapper = (UpdateWrapper<Picture>) wrappers.get(1);
        assertTrue(reviewWrapper.getSqlSet().contains("reviewStatus"), reviewWrapper.getSqlSet());
        assertTrue(reviewWrapper.getSqlSegment().contains("reviewStatus"),
                "自动处置必须带 reviewStatus = 待审核 条件: " + reviewWrapper.getSqlSegment());
        assertTrue(reviewWrapper.getParamNameValuePairs()
                .containsValue(PictureReviewStatusEnum.REJECT.getValue()));
        assertTrue(reviewWrapper.getParamNameValuePairs().containsValue(0L));
        String message = "机审违规（Porn，置信度 90），自动拒绝";
        verify(pictureReviewLogService).record(1L, PictureReviewLogService.SYSTEM_REVIEWER_ID,
                PictureReviewStatusEnum.REJECT.getValue(), message);
        verify(pictureListCache).invalidate();
        verify(pictureEventPublisher).pictureModerated(1L);
        verify(pictureEventPublisher).pictureChanged(1L);
    }

    @Test
    @DisplayName("auto-reject：正常图片仍走人工，不自动通过")
    void review_autoRejectNormalLeavesForHuman() {
        machineReviewProperties.setMode(MachineReviewModeEnum.AUTO_REJECT);
        Picture picture = picture(PictureReviewStatusEnum.REVIEWING.getValue());
        when(machineReviewManager.review(picture.getUrl()))
                .thenReturn(new MachineReviewResult(MachineReviewStatusEnum.NORMAL, "Normal", 99));
        when(pictureService.update(any(Wrapper.class))).thenReturn(true);

        machineReviewService.review(picture);

        capturedMachineWrapper(1);
        verifyNoInteractions(pictureReviewLogService, pictureListCache);
        verify(pictureEventPublisher, never()).pictureChanged(1L);
    }

    @Test
    @DisplayName("auto：正常图片自动通过")
    void review_autoPassesNormalPicture() {
        machineReviewProperties.setMode(MachineReviewModeEnum.AUTO);
        Picture picture = picture(PictureReviewStatusEnum.REVIEWING.getValue());
        when(machineReviewManager.review(picture.getUrl()))
                .thenReturn(new MachineReviewResult(MachineReviewStatusEnum.NORMAL, "Normal", 99));
        when(pictureService.update(any(Wrapper.class))).thenReturn(true, true);

        machineReviewService.review(picture);

        List<Wrapper<Picture>> wrappers = capturedWrappers(2);
        UpdateWrapper<Picture> reviewWrapper = (UpdateWrapper<Picture>) wrappers.get(1);
        assertTrue(reviewWrapper.getParamNameValuePairs()
                .containsValue(PictureReviewStatusEnum.PASS.getValue()));
        verify(pictureReviewLogService).record(1L, PictureReviewLogService.SYSTEM_REVIEWER_ID,
                PictureReviewStatusEnum.PASS.getValue(), "机审正常，自动通过");
        verify(pictureEventPublisher).pictureChanged(1L);
    }

    @Test
    @DisplayName("auto：疑似图片始终交给人工")
    void review_autoLeavesSuspectForHuman() {
        machineReviewProperties.setMode(MachineReviewModeEnum.AUTO);
        Picture picture = picture(PictureReviewStatusEnum.REVIEWING.getValue());
        when(machineReviewManager.review(picture.getUrl()))
                .thenReturn(new MachineReviewResult(MachineReviewStatusEnum.SUSPECT, "Ads", 60));
        when(pictureService.update(any(Wrapper.class))).thenReturn(true);

        machineReviewService.review(picture);

        capturedMachineWrapper(1);
        verifyNoInteractions(pictureReviewLogService, pictureListCache);
        verify(pictureEventPublisher, never()).pictureChanged(1L);
    }

    @Test
    @DisplayName("并发：人工审核先完成时只保留机审字段，不覆盖人工结论")
    void review_humanReviewWinsRace() {
        machineReviewProperties.setMode(MachineReviewModeEnum.AUTO);
        Picture picture = picture(PictureReviewStatusEnum.REVIEWING.getValue());
        when(machineReviewManager.review(picture.getUrl()))
                .thenReturn(new MachineReviewResult(MachineReviewStatusEnum.VIOLATION, "Porn", 90));
        // 机审字段写入成功，但自动处置的条件更新命中 0 行：管理员已先审核
        when(pictureService.update(any(Wrapper.class))).thenReturn(true, false);

        machineReviewService.review(picture);

        verify(pictureReviewLogService, never()).record(any(), any(), any(), any());
        verify(pictureListCache, never()).invalidate();
        verify(pictureEventPublisher).pictureModerated(1L);
        verify(pictureEventPublisher, never()).pictureChanged(1L);
    }

    @Test
    @DisplayName("非待审核图片直接跳过")
    void review_skipsReviewedPicture() {
        Picture picture = picture(PictureReviewStatusEnum.PASS.getValue());

        machineReviewService.review(picture);

        verifyNoInteractions(machineReviewManager, pictureService,
                pictureReviewLogService, pictureListCache, pictureEventPublisher);
    }

    @Test
    @DisplayName("机审接口失败时向上抛出，交给消息重试")
    void review_propagatesMachineReviewFailure() {
        Picture picture = picture(PictureReviewStatusEnum.REVIEWING.getValue());
        when(machineReviewManager.review(picture.getUrl()))
                .thenThrow(new MachineReviewException("api down"));

        assertThrows(MachineReviewException.class, () -> machineReviewService.review(picture));
        verifyNoInteractions(pictureService, pictureReviewLogService, pictureEventPublisher);
    }

    @Test
    @DisplayName("自动处置意见超长时截断到 512 个字符")
    void buildAutoReviewMessage_truncatesLongLabel() {
        MachineReviewResult result = new MachineReviewResult(
                MachineReviewStatusEnum.VIOLATION, "a".repeat(600), 100);

        String message = MachineReviewServiceImpl.buildAutoReviewMessage(
                result, PictureReviewStatusEnum.REJECT.getValue());

        assertEquals(512, message.length());
    }

    @Test
    @DisplayName("自动处置意见：正常与违规文案不同")
    void buildAutoReviewMessage_usesExpectedText() {
        MachineReviewResult normal = new MachineReviewResult(
                MachineReviewStatusEnum.NORMAL, "Normal", 99);
        MachineReviewResult violation = new MachineReviewResult(
                MachineReviewStatusEnum.VIOLATION, "Porn/SexyBehavior", 88);

        assertEquals("机审正常，自动通过", MachineReviewServiceImpl.buildAutoReviewMessage(
                normal, PictureReviewStatusEnum.PASS.getValue()));
        assertEquals("机审违规（Porn/SexyBehavior，置信度 88），自动拒绝",
                MachineReviewServiceImpl.buildAutoReviewMessage(
                        violation, PictureReviewStatusEnum.REJECT.getValue()));
    }

    @Test
    @DisplayName("重复调用不会因为同一图片重复写审核历史")
    void review_isIdempotentForSamePicture() {
        Picture picture = picture(PictureReviewStatusEnum.REVIEWING.getValue());
        when(machineReviewManager.review(picture.getUrl()))
                .thenReturn(new MachineReviewResult(MachineReviewStatusEnum.NORMAL, "Normal", 95));
        when(pictureService.update(any(Wrapper.class))).thenReturn(true, false);

        machineReviewService.review(picture);
        machineReviewService.review(picture);

        verify(pictureEventPublisher, times(1)).pictureModerated(1L);
        verifyNoInteractions(pictureReviewLogService);
    }

    private UpdateWrapper<Picture> capturedMachineWrapper(int callIndex) {
        return (UpdateWrapper<Picture>) capturedWrappers(callIndex).get(callIndex - 1);
    }

    @SuppressWarnings("unchecked")
    private List<Wrapper<Picture>> capturedWrappers(int expectedCalls) {
        ArgumentCaptor<Wrapper<Picture>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(pictureService, times(expectedCalls)).update(captor.capture());
        return captor.getAllValues();
    }

    private Picture picture(int reviewStatus) {
        Picture picture = new Picture();
        picture.setId(1L);
        picture.setReviewStatus(reviewStatus);
        picture.setUrl("https://bucket.cos.ap-beijing.myqcloud.com/public/1/a.jpg");
        return picture;
    }
}

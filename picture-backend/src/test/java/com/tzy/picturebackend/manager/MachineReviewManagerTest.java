package com.tzy.picturebackend.manager;

import com.qcloud.cos.model.ciModel.auditing.ImageAuditingResponse;
import com.tzy.picturebackend.model.enums.MachineReviewStatusEnum;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 机审响应解析：结果映射、标签拼接、置信度清洗与失败语义
 */
class MachineReviewManagerTest {

    private static final String URL = "https://bucket.cos.ap-beijing.myqcloud.com/public/1/a.jpg";

    private static final String OBJECT_KEY = "public/1/a.jpg";

    private CosManager cosManager;

    private MachineReviewManager machineReviewManager;

    @BeforeEach
    void setUp() {
        cosManager = mock(CosManager.class);
        machineReviewManager = new MachineReviewManager(cosManager);
    }

    @Test
    @DisplayName("正常图片：Result=0 映射为正常并带出标签与置信度")
    void review_mapsNormalResult() {
        when(cosManager.parseObjectKey(URL)).thenReturn(OBJECT_KEY);
        when(cosManager.auditImage(OBJECT_KEY)).thenReturn(response("0", "Normal", null, "95"));

        MachineReviewResult result = machineReviewManager.review(URL);

        assertEquals(MachineReviewStatusEnum.NORMAL, result.status());
        assertEquals("Normal", result.label());
        assertEquals(Integer.valueOf(95), result.score());
        verify(cosManager).auditImage(OBJECT_KEY);
    }

    @Test
    @DisplayName("违规图片：Result=1，主标签与子标签用斜杠拼接")
    void review_mapsViolationWithSubLabel() {
        when(cosManager.parseObjectKey(URL)).thenReturn(OBJECT_KEY);
        when(cosManager.auditImage(OBJECT_KEY)).thenReturn(response("1", "Porn", "SexyBehavior", "88"));

        MachineReviewResult result = machineReviewManager.review(URL);

        assertEquals(MachineReviewStatusEnum.VIOLATION, result.status());
        assertEquals("Porn/SexyBehavior", result.label());
        assertEquals(Integer.valueOf(88), result.score());
    }

    @Test
    @DisplayName("疑似图片：Result=2 映射为疑似")
    void review_mapsSuspectResult() {
        when(cosManager.parseObjectKey(URL)).thenReturn(OBJECT_KEY);
        when(cosManager.auditImage(OBJECT_KEY)).thenReturn(response("2", "Ads", null, "60"));

        MachineReviewResult result = machineReviewManager.review(URL);

        assertEquals(MachineReviewStatusEnum.SUSPECT, result.status());
    }

    @Test
    @DisplayName("失败：对象键无法解析时不调用审核接口")
    void review_rejectsUnparsableUrl() {
        when(cosManager.parseObjectKey(URL)).thenReturn(null);

        MachineReviewException e = assertThrows(MachineReviewException.class,
                () -> machineReviewManager.review(URL));

        assertTrue(e.getMessage().contains("对象键"));
        verify(cosManager, never()).auditImage(anyString());
    }

    @Test
    @DisplayName("失败：审核接口抛异常时包装成机审异常")
    void review_wrapsApiError() {
        when(cosManager.parseObjectKey(URL)).thenReturn(OBJECT_KEY);
        when(cosManager.auditImage(OBJECT_KEY)).thenThrow(new RuntimeException("AccessDenied"));

        MachineReviewException e = assertThrows(MachineReviewException.class,
                () -> machineReviewManager.review(URL));

        assertTrue(e.getCause() instanceof RuntimeException);
    }

    @Test
    @DisplayName("失败：空响应或无法识别的 Result 视为机审失败，不能默认放行")
    void review_rejectsUnknownResult() {
        when(cosManager.parseObjectKey(URL)).thenReturn(OBJECT_KEY);
        when(cosManager.auditImage(OBJECT_KEY)).thenReturn(response(null, "Normal", null, "90"));

        assertThrows(MachineReviewException.class, () -> machineReviewManager.review(URL));
    }

    @Test
    @DisplayName("失败：审核接口返回 null 时视为失败")
    void review_rejectsNullResponse() {
        when(cosManager.parseObjectKey(URL)).thenReturn(OBJECT_KEY);
        when(cosManager.auditImage(OBJECT_KEY)).thenReturn(null);

        assertThrows(MachineReviewException.class, () -> machineReviewManager.review(URL));
    }

    @Test
    @DisplayName("标签：主标签为空用 Unknown，超过 64 个字符时截断")
    void buildLabel_normalizesAndTruncates() {
        assertEquals("Unknown", MachineReviewManager.buildLabel(null, null));
        assertEquals(64, MachineReviewManager.buildLabel("a".repeat(80), null).length());
        assertEquals("Porn/SexyBehavior", MachineReviewManager.buildLabel("Porn", "SexyBehavior"));
    }

    @Test
    @DisplayName("置信度：非法或超出 0-100 时写 null")
    void parseScore_handlesInvalidValues() {
        assertNull(MachineReviewManager.parseScore("abc"));
        assertNull(MachineReviewManager.parseScore("-1"));
        assertNull(MachineReviewManager.parseScore("101"));
        assertNull(MachineReviewManager.parseScore(" "));
        assertEquals(Integer.valueOf(0), MachineReviewManager.parseScore(" 0 "));
        assertEquals(Integer.valueOf(100), MachineReviewManager.parseScore("100"));
    }

    private ImageAuditingResponse response(String result, String label, String subLabel, String score) {
        ImageAuditingResponse response = new ImageAuditingResponse();
        response.setResult(result);
        response.setLabel(label);
        response.setSubLabel(subLabel);
        response.setScore(score);
        return response;
    }
}

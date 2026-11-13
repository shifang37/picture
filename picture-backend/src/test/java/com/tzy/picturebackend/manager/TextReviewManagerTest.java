package com.tzy.picturebackend.manager;

import com.qcloud.cos.model.ciModel.auditing.AdsInfo;
import com.qcloud.cos.model.ciModel.auditing.AuditingJobsDetail;
import com.qcloud.cos.model.ciModel.auditing.PoliticsInfo;
import com.qcloud.cos.model.ciModel.auditing.PornInfo;
import com.qcloud.cos.model.ciModel.auditing.TextAuditingResponse;
import com.tzy.picturebackend.config.TextReviewProperties;
import com.tzy.picturebackend.model.enums.TextReviewStatusEnum;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 文本审核响应解析与轮询：结果映射、标签拼接、置信度清洗、失败与超时语义
 */
class TextReviewManagerTest {

    private CosManager cosManager;

    private TextReviewProperties textReviewProperties;

    private TextReviewManager textReviewManager;

    @BeforeEach
    void setUp() {
        cosManager = mock(CosManager.class);
        textReviewProperties = new TextReviewProperties();
        textReviewProperties.setPollIntervalMs(20);
        textReviewProperties.setPollTimeoutMs(300);
        textReviewManager = new TextReviewManager(cosManager, textReviewProperties);
    }

    @Test
    @DisplayName("违规文本：Result=1，主标签与子标签用斜杠拼接并带置信度")
    void review_mapsViolationWithSubLabel() {
        when(cosManager.createTextAuditingJob(anyString(), anyString())).thenReturn(created("job-1"));
        when(cosManager.describeTextAuditingJob("job-1"))
                .thenReturn(response(detail("Success", "1", "Porn", "SexyBehavior", 88)));

        TextReviewResult result = textReviewManager.review("违规文本");

        assertEquals(TextReviewStatusEnum.VIOLATION, result.status());
        assertEquals("Porn/SexyBehavior", result.label());
        assertEquals(Integer.valueOf(88), result.score());
        verify(cosManager).describeTextAuditingJob("job-1");
    }

    @Test
    @DisplayName("提交内容按接口要求做 Base64 编码，并带上配置的审核策略")
    void review_sendsBase64ContentAndBizType() {
        textReviewProperties.setBizType("biz-123");
        when(cosManager.createTextAuditingJob(anyString(), anyString())).thenReturn(created("job-1"));
        when(cosManager.describeTextAuditingJob("job-1"))
                .thenReturn(response(detail("Success", "0", "Normal", null, null)));

        textReviewManager.review("名称\n简介");

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(cosManager).createTextAuditingJob(payload.capture(), org.mockito.ArgumentMatchers.eq("biz-123"));
        assertEquals("名称\n简介",
                new String(Base64.getDecoder().decode(payload.getValue()), StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("任务未完成时轮询到 Success 再返回")
    void review_pollsUntilSuccess() {
        when(cosManager.createTextAuditingJob(anyString(), anyString())).thenReturn(created("job-2"));
        when(cosManager.describeTextAuditingJob("job-2"))
                .thenReturn(response(detail("Running", null, null, null, null)))
                .thenReturn(response(detail("Success", "0", "Normal", null, null)));

        TextReviewResult result = textReviewManager.review("正常文本");

        assertEquals(TextReviewStatusEnum.NORMAL, result.status());
        assertEquals("Normal", result.label());
        assertNull(result.score());
        verify(cosManager, times(2)).describeTextAuditingJob("job-2");
    }

    @Test
    @DisplayName("创建响应直接带终态时不再查询任务")
    void review_createResponseWithFinalStateSkipsDescribe() {
        AuditingJobsDetail done = detail("Success", "2", "Ads", null, 60);
        done.setJobId("job-3");
        when(cosManager.createTextAuditingJob(anyString(), anyString())).thenReturn(response(done));

        TextReviewResult result = textReviewManager.review("疑似文本");

        assertEquals(TextReviewStatusEnum.SUSPECT, result.status());
        assertEquals("Ads", result.label());
        verify(cosManager, never()).describeTextAuditingJob(anyString());
    }

    @Test
    @DisplayName("置信度取各审核场景中的最大值，非法值忽略")
    void review_takesMaxScoreAcrossCategories() {
        AuditingJobsDetail detail = detail("Success", "1", "Porn", null, 40);
        AdsInfo ads = new AdsInfo();
        ads.setScore("85");
        detail.setAdsInfo(ads);
        PoliticsInfo invalid = new PoliticsInfo();
        invalid.setScore("abc");
        detail.setPoliticsInfo(invalid);

        assertEquals(Integer.valueOf(85), TextReviewManager.maxScore(detail));
    }

    @Test
    @DisplayName("任务失败：抛出 TextReviewException 并带 code / message")
    void review_failedJobThrows() {
        when(cosManager.createTextAuditingJob(anyString(), anyString())).thenReturn(created("job-4"));
        AuditingJobsDetail failed = detail("Failed", null, null, null, null);
        failed.setCode("400");
        failed.setMessage("invalid content");
        when(cosManager.describeTextAuditingJob("job-4")).thenReturn(response(failed));

        TextReviewException exception = assertThrows(TextReviewException.class,
                () -> textReviewManager.review("文本"));

        assertTrue(exception.getMessage().contains("invalid content"), exception.getMessage());
        assertTrue(exception.getMessage().contains("job-4"), exception.getMessage());
    }

    @Test
    @DisplayName("超过等待时间仍未完成：抛出超时异常")
    void review_timesOut() {
        textReviewProperties.setPollTimeoutMs(40);
        when(cosManager.createTextAuditingJob(anyString(), anyString())).thenReturn(created("job-5"));
        when(cosManager.describeTextAuditingJob("job-5"))
                .thenReturn(response(detail("Running", null, null, null, null)));

        TextReviewException exception = assertThrows(TextReviewException.class,
                () -> textReviewManager.review("文本"));

        assertTrue(exception.getMessage().contains("超时"), exception.getMessage());
    }

    @Test
    @DisplayName("创建任务没有返回 JobId：直接失败，避免空轮询")
    void review_missingJobIdThrows() {
        when(cosManager.createTextAuditingJob(anyString(), anyString()))
                .thenReturn(response(detail("Running", null, null, null, null)));

        assertThrows(TextReviewException.class, () -> textReviewManager.review("文本"));
    }

    @Test
    @DisplayName("创建任务返回空响应：抛出异常")
    void review_nullCreateResponseThrows() {
        when(cosManager.createTextAuditingJob(anyString(), anyString())).thenReturn(null);

        assertThrows(TextReviewException.class, () -> textReviewManager.review("文本"));
    }

    @Test
    @DisplayName("无法识别的 Result：抛出异常而不是写入脏数据")
    void review_unknownResultThrows() {
        when(cosManager.createTextAuditingJob(anyString(), anyString())).thenReturn(created("job-6"));
        when(cosManager.describeTextAuditingJob("job-6"))
                .thenReturn(response(detail("Success", "9", "Unknown", null, null)));

        assertThrows(TextReviewException.class, () -> textReviewManager.review("文本"));
    }

    @Test
    @DisplayName("创建接口异常：包装成 TextReviewException")
    void review_createCallFailureIsWrapped() {
        when(cosManager.createTextAuditingJob(anyString(), anyString()))
                .thenThrow(new IllegalStateException("network down"));

        TextReviewException exception = assertThrows(TextReviewException.class,
                () -> textReviewManager.review("文本"));

        assertTrue(exception.getCause() instanceof IllegalStateException);
    }

    @Test
    @DisplayName("空文本不发起调用")
    void review_blankContentRejected() {
        assertThrows(TextReviewException.class, () -> textReviewManager.review("  "));
        verify(cosManager, never()).createTextAuditingJob(anyString(), anyString());
    }

    @Test
    @DisplayName("标签超长时截断到 64 个字符，缺失时使用 Unknown")
    void buildLabel_truncatesAndFallsBack() {
        assertEquals(64, TextReviewManager.buildLabel("a".repeat(80), "b").length());
        assertEquals("Unknown", TextReviewManager.buildLabel(null, null));
        assertEquals("Porn/SexyBehavior", TextReviewManager.buildLabel("Porn", "SexyBehavior"));
    }

    @Test
    @DisplayName("置信度只接受 0-100 的整数")
    void parseScore_validatesRange() {
        assertNull(TextReviewManager.parseScore(null));
        assertNull(TextReviewManager.parseScore("101"));
        assertNull(TextReviewManager.parseScore("-1"));
        assertNull(TextReviewManager.parseScore("x"));
        assertEquals(Integer.valueOf(0), TextReviewManager.parseScore("0"));
        assertEquals(Integer.valueOf(100), TextReviewManager.parseScore("100"));
    }

    private static TextAuditingResponse created(String jobId) {
        AuditingJobsDetail detail = new AuditingJobsDetail();
        detail.setJobId(jobId);
        detail.setState("Submitted");
        return response(detail);
    }

    private static TextAuditingResponse response(AuditingJobsDetail detail) {
        TextAuditingResponse response = new TextAuditingResponse();
        response.setJobsDetail(detail);
        return response;
    }

    private static AuditingJobsDetail detail(String state, String result, String label,
                                             String subLabel, Integer score) {
        AuditingJobsDetail detail = new AuditingJobsDetail();
        detail.setState(state);
        detail.setResult(result);
        detail.setLabel(label);
        detail.setSubLabel(subLabel);
        if (score != null) {
            PornInfo pornInfo = new PornInfo();
            pornInfo.setScore(String.valueOf(score));
            detail.setPornInfo(pornInfo);
        }
        return detail;
    }
}

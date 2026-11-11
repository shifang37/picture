package com.tzy.picturebackend.controller;

import com.tzy.picturebackend.exception.BusinessException;
import com.tzy.picturebackend.exception.ErrorCode;
import com.tzy.picturebackend.exception.GlobalExceptionHandler;
import com.tzy.picturebackend.model.vo.AnalyzeCategoryVO;
import com.tzy.picturebackend.model.vo.AnalyzeOverviewVO;
import com.tzy.picturebackend.model.vo.AnalyzeReviewVO;
import com.tzy.picturebackend.model.vo.AnalyzeTagVO;
import com.tzy.picturebackend.model.vo.AnalyzeTopDownloadVO;
import com.tzy.picturebackend.model.vo.AnalyzeUploadTrendVO;
import com.tzy.picturebackend.service.AnalyzeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 数据看板接口测试：返回值与查询参数的默认值/透传。
 * <p>
 * 管理员权限由 {@code @AuthCheck} 切面保证，standalone MockMvc 不加载 AOP，
 * 权限链路在 {@code PictureApplicationIT} 中验证。
 */
class AnalyzeControllerTest {

    private MockMvc mockMvc;

    private AnalyzeService analyzeService;

    @BeforeEach
    void setUp() {
        analyzeService = mock(AnalyzeService.class);
        AnalyzeController analyzeController = new AnalyzeController();
        ReflectionTestUtils.setField(analyzeController, "analyzeService", analyzeService);
        mockMvc = MockMvcBuilders.standaloneSetup(analyzeController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("概览：返回各核心指标")
    void getOverview_returnsMetrics() throws Exception {
        AnalyzeOverviewVO overview = new AnalyzeOverviewVO();
        overview.setUserCount(12L);
        overview.setPictureCount(34L);
        overview.setPendingReviewCount(5L);
        overview.setTotalPicSize(1024L);
        overview.setTotalDownloadCount(99L);
        when(analyzeService.getOverview()).thenReturn(overview);

        mockMvc.perform(get("/analyze/overview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.userCount").value(12))
                .andExpect(jsonPath("$.data.pictureCount").value(34))
                .andExpect(jsonPath("$.data.pendingReviewCount").value(5))
                .andExpect(jsonPath("$.data.totalPicSize").value(1024))
                .andExpect(jsonPath("$.data.totalDownloadCount").value(99));
    }

    @Test
    @DisplayName("上传趋势：默认 30 天，显式参数透传")
    void getUploadTrend_usesDefaultAndExplicitDays() throws Exception {
        List<AnalyzeUploadTrendVO> trend = List.of(new AnalyzeUploadTrendVO());
        when(analyzeService.getUploadTrend(anyInt())).thenReturn(trend);

        mockMvc.perform(get("/analyze/upload_trend"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        verify(analyzeService).getUploadTrend(30);

        mockMvc.perform(get("/analyze/upload_trend").param("days", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        verify(analyzeService).getUploadTrend(7);
    }

    @Test
    @DisplayName("上传趋势：非法天数由服务层校验，返回参数错误")
    void getUploadTrend_rejectsInvalidDays() throws Exception {
        when(analyzeService.getUploadTrend(0))
                .thenThrow(new BusinessException(ErrorCode.PARAMS_ERROR, "天数必须在 1-365 之间"));

        mockMvc.perform(get("/analyze/upload_trend").param("days", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ErrorCode.PARAMS_ERROR.getCode()))
                .andExpect(jsonPath("$.message").value("天数必须在 1-365 之间"));
    }

    @Test
    @DisplayName("分类：返回分类统计列表")
    void getCategory_returnsList() throws Exception {
        AnalyzeCategoryVO category = new AnalyzeCategoryVO();
        category.setCategory("风景");
        category.setCount(3L);
        when(analyzeService.getCategoryStats()).thenReturn(List.of(category));

        mockMvc.perform(get("/analyze/category"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[0].category").value("风景"))
                .andExpect(jsonPath("$.data[0].count").value(3));
    }

    @Test
    @DisplayName("标签：默认 20 条，显式 limit 透传")
    void getTags_usesDefaultAndExplicitLimit() throws Exception {
        when(analyzeService.getTopTags(anyInt())).thenReturn(List.of(new AnalyzeTagVO()));

        mockMvc.perform(get("/analyze/tags"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        verify(analyzeService).getTopTags(20);

        mockMvc.perform(get("/analyze/tags").param("limit", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        verify(analyzeService).getTopTags(5);
    }

    @Test
    @DisplayName("审核：返回通过、拒绝数量与平均耗时")
    void getReview_returnsStats() throws Exception {
        AnalyzeReviewVO review = new AnalyzeReviewVO();
        review.setApprovedCount(8L);
        review.setRejectedCount(2L);
        review.setAverageReviewSeconds(120.0);
        when(analyzeService.getReviewStats()).thenReturn(review);

        mockMvc.perform(get("/analyze/review"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.approvedCount").value(8))
                .andExpect(jsonPath("$.data.rejectedCount").value(2))
                .andExpect(jsonPath("$.data.averageReviewSeconds").value(120.0));
    }

    @Test
    @DisplayName("下载排行：默认 10 条，显式 limit 透传")
    void getTopDownload_usesDefaultAndExplicitLimit() throws Exception {
        when(analyzeService.getTopDownload(anyInt())).thenReturn(List.of(new AnalyzeTopDownloadVO()));

        mockMvc.perform(get("/analyze/top_download"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        verify(analyzeService).getTopDownload(10);

        mockMvc.perform(get("/analyze/top_download").param("limit", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        verify(analyzeService).getTopDownload(3);
    }
}

package com.tzy.picturebackend.service.impl;

import com.tzy.picturebackend.exception.BusinessException;
import com.tzy.picturebackend.exception.ErrorCode;
import com.tzy.picturebackend.mapper.AnalyzeMapper;
import com.tzy.picturebackend.model.vo.AnalyzeCategoryVO;
import com.tzy.picturebackend.model.vo.AnalyzeOverviewVO;
import com.tzy.picturebackend.model.vo.AnalyzeReviewVO;
import com.tzy.picturebackend.model.vo.AnalyzeTagVO;
import com.tzy.picturebackend.model.vo.AnalyzeTopDownloadVO;
import com.tzy.picturebackend.model.vo.AnalyzeUploadTrendVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 数据看板统计服务测试：参数校验与 Mapper 委托
 */
class AnalyzeServiceImplTest {

    private AnalyzeServiceImpl analyzeService;

    private AnalyzeMapper analyzeMapper;

    @BeforeEach
    void setUp() {
        analyzeService = new AnalyzeServiceImpl();
        analyzeMapper = mock(AnalyzeMapper.class);
        ReflectionTestUtils.setField(analyzeService, "analyzeMapper", analyzeMapper);
    }

    @Test
    @DisplayName("概览：直接返回 Mapper 聚合结果")
    void getOverview_returnsMapperResult() {
        AnalyzeOverviewVO overview = new AnalyzeOverviewVO();
        overview.setUserCount(10L);
        when(analyzeMapper.selectOverview()).thenReturn(overview);

        assertSame(overview, analyzeService.getOverview());
        verify(analyzeMapper).selectOverview();
    }

    @Test
    @DisplayName("上传趋势：30 天换算为起始偏移 29 天")
    void getUploadTrend_usesOffsetDays() {
        List<AnalyzeUploadTrendVO> trend = List.of(new AnalyzeUploadTrendVO());
        when(analyzeMapper.selectUploadTrend(29)).thenReturn(trend);

        assertSame(trend, analyzeService.getUploadTrend(30));
        verify(analyzeMapper).selectUploadTrend(29);
    }

    @Test
    @DisplayName("上传趋势：天数超出 1-365 返回参数错误且不查库")
    void getUploadTrend_rejectsInvalidDays() {
        assertParamsError(() -> analyzeService.getUploadTrend(0));
        assertParamsError(() -> analyzeService.getUploadTrend(366));
        verifyNoInteractions(analyzeMapper);
    }

    @Test
    @DisplayName("分类：直接返回 Mapper 聚合结果")
    void getCategoryStats_returnsMapperResult() {
        List<AnalyzeCategoryVO> categories = List.of(new AnalyzeCategoryVO());
        when(analyzeMapper.selectCategoryStats()).thenReturn(categories);

        assertSame(categories, analyzeService.getCategoryStats());
        verify(analyzeMapper).selectCategoryStats();
    }

    @Test
    @DisplayName("标签排行：limit 透传，超出 1-50 返回参数错误")
    void getTopTags_validatesAndPassesLimit() {
        List<AnalyzeTagVO> tags = List.of(new AnalyzeTagVO());
        when(analyzeMapper.selectTopTags(20)).thenReturn(tags);

        assertSame(tags, analyzeService.getTopTags(20));
        verify(analyzeMapper).selectTopTags(20);

        assertParamsError(() -> analyzeService.getTopTags(0));
        assertParamsError(() -> analyzeService.getTopTags(51));
    }

    @Test
    @DisplayName("审核统计：直接返回 Mapper 聚合结果")
    void getReviewStats_returnsMapperResult() {
        AnalyzeReviewVO review = new AnalyzeReviewVO();
        review.setApprovedCount(3L);
        when(analyzeMapper.selectReviewStats()).thenReturn(review);

        assertSame(review, analyzeService.getReviewStats());
        verify(analyzeMapper).selectReviewStats();
    }

    @Test
    @DisplayName("下载排行：limit 透传，超出 1-50 返回参数错误")
    void getTopDownload_validatesAndPassesLimit() {
        List<AnalyzeTopDownloadVO> pictures = List.of(new AnalyzeTopDownloadVO());
        when(analyzeMapper.selectTopDownload(10)).thenReturn(pictures);

        assertSame(pictures, analyzeService.getTopDownload(10));
        verify(analyzeMapper).selectTopDownload(10);

        assertParamsError(() -> analyzeService.getTopDownload(0));
        assertParamsError(() -> analyzeService.getTopDownload(51));
    }

    private void assertParamsError(Runnable action) {
        BusinessException exception = assertThrows(BusinessException.class, action::run);
        assertEquals(ErrorCode.PARAMS_ERROR.getCode(), exception.getCode());
    }
}

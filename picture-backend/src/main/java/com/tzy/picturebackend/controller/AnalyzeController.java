package com.tzy.picturebackend.controller;

import com.tzy.picturebackend.annotation.AuthCheck;
import com.tzy.picturebackend.common.BaseResponse;
import com.tzy.picturebackend.common.ResultUtils;
import com.tzy.picturebackend.constant.UserConstant;
import com.tzy.picturebackend.model.vo.AnalyzeCategoryVO;
import com.tzy.picturebackend.model.vo.AnalyzeOverviewVO;
import com.tzy.picturebackend.model.vo.AnalyzeReviewVO;
import com.tzy.picturebackend.model.vo.AnalyzeTagVO;
import com.tzy.picturebackend.model.vo.AnalyzeTopDownloadVO;
import com.tzy.picturebackend.model.vo.AnalyzeUploadTrendVO;
import com.tzy.picturebackend.service.AnalyzeService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 管理端数据看板接口（仅管理员）。
 * <p>
 * 统计结果在后端缓存 5 分钟，前端刷新不保证实时。
 */
@RestController
@RequestMapping("/analyze")
public class AnalyzeController {

    @Resource
    private AnalyzeService analyzeService;

    /**
     * 核心指标：用户数、图片数、待审核数、总存储体积、总下载次数
     */
    @GetMapping("/overview")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<AnalyzeOverviewVO> getOverview() {
        return ResultUtils.success(analyzeService.getOverview());
    }

    /**
     * 最近 N 天的每日上传数量
     */
    @GetMapping("/upload_trend")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<List<AnalyzeUploadTrendVO>> getUploadTrend(
            @RequestParam(defaultValue = "30") int days) {
        return ResultUtils.success(analyzeService.getUploadTrend(days));
    }

    /**
     * 各分类的图片数量与体积
     */
    @GetMapping("/category")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<List<AnalyzeCategoryVO>> getCategoryStats() {
        return ResultUtils.success(analyzeService.getCategoryStats());
    }

    /**
     * 使用次数最多的标签
     */
    @GetMapping("/tags")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<List<AnalyzeTagVO>> getTopTags(
            @RequestParam(defaultValue = "20") int limit) {
        return ResultUtils.success(analyzeService.getTopTags(limit));
    }

    /**
     * 审核统计：通过/拒绝数量与平均审核耗时
     */
    @GetMapping("/review")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<AnalyzeReviewVO> getReviewStats() {
        return ResultUtils.success(analyzeService.getReviewStats());
    }

    /**
     * 下载次数排行
     */
    @GetMapping("/top_download")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<List<AnalyzeTopDownloadVO>> getTopDownload(
            @RequestParam(defaultValue = "10") int limit) {
        return ResultUtils.success(analyzeService.getTopDownload(limit));
    }
}

package com.tzy.picturebackend.service;

import com.tzy.picturebackend.model.vo.AnalyzeCategoryVO;
import com.tzy.picturebackend.model.vo.AnalyzeOverviewVO;
import com.tzy.picturebackend.model.vo.AnalyzeReviewVO;
import com.tzy.picturebackend.model.vo.AnalyzeTagVO;
import com.tzy.picturebackend.model.vo.AnalyzeTopDownloadVO;
import com.tzy.picturebackend.model.vo.AnalyzeUploadTrendVO;

import java.util.List;

/**
 * 管理端数据看板统计服务（结果缓存 5 分钟）
 */
public interface AnalyzeService {

    /**
     * 核心指标概览
     */
    AnalyzeOverviewVO getOverview();

    /**
     * 最近 days 天的每日上传数量
     */
    List<AnalyzeUploadTrendVO> getUploadTrend(int days);

    /**
     * 各分类图片数量与体积
     */
    List<AnalyzeCategoryVO> getCategoryStats();

    /**
     * 使用次数最多的标签
     */
    List<AnalyzeTagVO> getTopTags(int limit);

    /**
     * 审核统计
     */
    AnalyzeReviewVO getReviewStats();

    /**
     * 下载次数排行
     */
    List<AnalyzeTopDownloadVO> getTopDownload(int limit);
}

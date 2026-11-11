package com.tzy.picturebackend.service.impl;

import com.tzy.picturebackend.config.CacheConfig;
import com.tzy.picturebackend.exception.ErrorCode;
import com.tzy.picturebackend.exception.ThrowUtils;
import com.tzy.picturebackend.mapper.AnalyzeMapper;
import com.tzy.picturebackend.model.vo.AnalyzeCategoryVO;
import com.tzy.picturebackend.model.vo.AnalyzeOverviewVO;
import com.tzy.picturebackend.model.vo.AnalyzeReviewVO;
import com.tzy.picturebackend.model.vo.AnalyzeTagVO;
import com.tzy.picturebackend.model.vo.AnalyzeTopDownloadVO;
import com.tzy.picturebackend.model.vo.AnalyzeUploadTrendVO;
import com.tzy.picturebackend.service.AnalyzeService;
import jakarta.annotation.Resource;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 管理端数据看板统计服务实现。
 * <p>
 * 聚合查询本身不重，但看板会同时请求六个接口，且数据允许短暂滞后，
 * 因此统一用 Spring Cache 缓存 5 分钟（Redis，配置见 {@link CacheConfig}）。
 */
@Service
public class AnalyzeServiceImpl implements AnalyzeService {

    /**
     * 趋势最多查询一年，避免递归 CTE 生成过多日期
     */
    private static final int MAX_TREND_DAYS = 365;

    /**
     * 排行类接口最多返回 50 条
     */
    private static final int MAX_LIMIT = 50;

    @Resource
    private AnalyzeMapper analyzeMapper;

    @Override
    @Cacheable(cacheNames = CacheConfig.ANALYZE_CACHE, key = "'overview'")
    public AnalyzeOverviewVO getOverview() {
        return analyzeMapper.selectOverview();
    }

    @Override
    @Cacheable(cacheNames = CacheConfig.ANALYZE_CACHE, key = "'uploadTrend:' + #days")
    public List<AnalyzeUploadTrendVO> getUploadTrend(int days) {
        ThrowUtils.throwIf(days < 1 || days > MAX_TREND_DAYS, ErrorCode.PARAMS_ERROR,
                "天数必须在 1-" + MAX_TREND_DAYS + " 之间");
        // 查询天数包含今天，所以起始日期要往前推 days - 1 天
        return analyzeMapper.selectUploadTrend(days - 1);
    }

    @Override
    @Cacheable(cacheNames = CacheConfig.ANALYZE_CACHE, key = "'category'")
    public List<AnalyzeCategoryVO> getCategoryStats() {
        return analyzeMapper.selectCategoryStats();
    }

    @Override
    @Cacheable(cacheNames = CacheConfig.ANALYZE_CACHE, key = "'tags:' + #limit")
    public List<AnalyzeTagVO> getTopTags(int limit) {
        ThrowUtils.throwIf(limit < 1 || limit > MAX_LIMIT, ErrorCode.PARAMS_ERROR,
                "数量必须在 1-" + MAX_LIMIT + " 之间");
        return analyzeMapper.selectTopTags(limit);
    }

    @Override
    @Cacheable(cacheNames = CacheConfig.ANALYZE_CACHE, key = "'review'")
    public AnalyzeReviewVO getReviewStats() {
        return analyzeMapper.selectReviewStats();
    }

    @Override
    @Cacheable(cacheNames = CacheConfig.ANALYZE_CACHE, key = "'topDownload:' + #limit")
    public List<AnalyzeTopDownloadVO> getTopDownload(int limit) {
        ThrowUtils.throwIf(limit < 1 || limit > MAX_LIMIT, ErrorCode.PARAMS_ERROR,
                "数量必须在 1-" + MAX_LIMIT + " 之间");
        return analyzeMapper.selectTopDownload(limit);
    }
}

package com.tzy.picturebackend.mapper;

import com.tzy.picturebackend.model.vo.AnalyzeCategoryVO;
import com.tzy.picturebackend.model.vo.AnalyzeOverviewVO;
import com.tzy.picturebackend.model.vo.AnalyzeReviewVO;
import com.tzy.picturebackend.model.vo.AnalyzeTagVO;
import com.tzy.picturebackend.model.vo.AnalyzeTopDownloadVO;
import com.tzy.picturebackend.model.vo.AnalyzeUploadTrendVO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 管理端数据看板聚合查询。
 * <p>
 * 所有查询都手写 {@code isDelete = 0}：自定义 SQL 不会经过 MyBatis-Plus 的逻辑删除插件。
 */
public interface AnalyzeMapper {

    /**
     * 用户数、图片数、待审核数、总存储体积、总下载次数
     */
    AnalyzeOverviewVO selectOverview();

    /**
     * 最近若干天的每日上传数量（包含没有上传的日期）
     *
     * @param offsetDays 起始日期距今天的天数，例如查 30 天传 29
     */
    List<AnalyzeUploadTrendVO> selectUploadTrend(@Param("offsetDays") int offsetDays);

    /**
     * 各分类的图片数量与体积
     */
    List<AnalyzeCategoryVO> selectCategoryStats();

    /**
     * 使用次数最多的标签
     */
    List<AnalyzeTagVO> selectTopTags(@Param("limit") int limit);

    /**
     * 通过/拒绝数量与平均审核耗时
     */
    AnalyzeReviewVO selectReviewStats();

    /**
     * 下载次数最多的图片
     */
    List<AnalyzeTopDownloadVO> selectTopDownload(@Param("limit") int limit);
}

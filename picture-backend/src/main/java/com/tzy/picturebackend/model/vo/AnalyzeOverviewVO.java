package com.tzy.picturebackend.model.vo;

import lombok.Data;

/**
 * 管理端看板：核心指标概览
 */
@Data
public class AnalyzeOverviewVO {

    /**
     * 用户总数（未删除）
     */
    private Long userCount;

    /**
     * 图片总数（未删除）
     */
    private Long pictureCount;

    /**
     * 待审核图片数
     */
    private Long pendingReviewCount;

    /**
     * 图片总存储体积（字节）
     */
    private Long totalPicSize;

    /**
     * 下载总次数
     */
    private Long totalDownloadCount;
}

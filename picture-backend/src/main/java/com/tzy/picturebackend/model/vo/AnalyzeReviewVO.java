package com.tzy.picturebackend.model.vo;

import lombok.Data;

/**
 * 管理端看板：审核统计
 */
@Data
public class AnalyzeReviewVO {

    /**
     * 已通过审核的图片数
     */
    private Long approvedCount;

    /**
     * 已拒绝的图片数
     */
    private Long rejectedCount;

    /**
     * 平均审核耗时（秒），仅统计已审核且 reviewTime 不为空的图片
     */
    private Double averageReviewSeconds;
}

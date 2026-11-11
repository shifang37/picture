package com.tzy.picturebackend.model.vo;

import lombok.Data;

/**
 * 管理端看板：分类统计
 */
@Data
public class AnalyzeCategoryVO {

    /**
     * 分类名称，空分类归入「未分类」
     */
    private String category;

    /**
     * 图片数量
     */
    private Long count;

    /**
     * 分类下图片总存储体积（字节）
     */
    private Long totalSize;
}

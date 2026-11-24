package com.tzy.picturebackend.model.vo.rag;

import lombok.Data;

import java.io.Serializable;

/**
 * 问答引用的一张图片。
 * <p>
 * 只包含公开字段：名称、图片地址、缩略图地址与「为什么被引用」的短理由。
 * 未过审 / 私密图片永远不会出现在这里（服务层回表二次过滤）。
 */
@Data
public class RagCitationVO implements Serializable {

    /**
     * 图片 id
     */
    private Long id;

    /**
     * 图片名称
     */
    private String name;

    /**
     * 原图地址
     */
    private String pictureUrl;

    /**
     * 缩略图地址（详情页 / 引用卡片直接展示）
     */
    private String thumbnailUrl;

    /**
     * 引用理由：由代码根据分类、标签与尺寸拼接，不采用模型生成内容，避免二次幻觉
     */
    private String reason;

    private static final long serialVersionUID = 1L;
}

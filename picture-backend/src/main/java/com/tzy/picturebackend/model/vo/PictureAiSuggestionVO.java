package com.tzy.picturebackend.model.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.Date;
import java.util.List;

/**
 * 图片 AI 建议（只读）
 */
@Data
public class PictureAiSuggestionVO implements Serializable {

    /**
     * 图片 id
     */
    private Long pictureId;

    /**
     * 建议名称
     */
    private String name;

    /**
     * 建议简介
     */
    private String introduction;

    /**
     * 建议分类
     */
    private String category;

    /**
     * 建议标签
     */
    private List<String> tags;

    /**
     * 模型标识
     */
    private String model;

    /**
     * 状态：0-处理中 1-成功 2-失败
     */
    private Integer status;

    /**
     * 创建时间
     */
    private Date createTime;

    private static final long serialVersionUID = 1L;
}

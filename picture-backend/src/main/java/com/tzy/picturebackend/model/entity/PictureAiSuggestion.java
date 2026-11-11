package com.tzy.picturebackend.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 图片 AI 建议
 */
@TableName("picture_ai_suggestion")
@Data
public class PictureAiSuggestion implements Serializable {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

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
     * 建议标签（JSON 数组）
     */
    private String tags;

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

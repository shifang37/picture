package com.tzy.picturebackend.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 图片审核记录
 */
@TableName(value = "picture_review_log")
@Data
public class PictureReviewLog implements Serializable {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 图片 id
     */
    private Long pictureId;

    /**
     * 审核人 id
     */
    private Long reviewerId;

    /**
     * 审核结果：1-通过 2-拒绝
     */
    private Integer reviewStatus;

    /**
     * 审核意见
     */
    private String reviewMessage;

    /**
     * 审核时间
     */
    private Date createTime;

    private static final long serialVersionUID = 1L;
}

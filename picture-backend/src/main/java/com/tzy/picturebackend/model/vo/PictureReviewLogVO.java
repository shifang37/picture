package com.tzy.picturebackend.model.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 图片审核历史（含审核人信息）
 */
@Data
public class PictureReviewLogVO implements Serializable {

    /**
     * 审核记录 id
     */
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
     * 审核人昵称，用户已删除时为「用户#id」
     */
    private String reviewerName;

    /**
     * 审核人账号
     */
    private String reviewerAccount;

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

package com.tzy.picturebackend.model.entity;

import com.baomidou.mybatisplus.annotation.*;

import java.io.Serializable;
import java.util.Date;
import lombok.Data;

/**
 * 图片
 * @TableName picture
 */
@TableName(value ="picture")
@Data
public class Picture implements Serializable {
    @TableField(exist = false)
    private static final long serialVersionUID = -9058238645290575536L;
    /**
     * id
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 图片 url
     */
    private String url;

    /**
     * 图片名称
     */
    private String name;

    /**
     * 简介
     */
    private String introduction;

    /**
     * 分类
     */
    private String category;

    /**
     * 标签（JSON 数组）
     */
    private String tags;

    /**
     * 图片体积
     */
    private Long picSize;

    /**
     * 下载次数
     */
    private Long downloadCount;

    /**
     * 收藏数（picture_interaction 的冗余汇总，列表排序与展示用）
     */
    private Long favoriteCount;

    /**
     * 点赞数（picture_interaction 的冗余汇总，列表排序与展示用）
     */
    private Long likeCount;

    /**
     * 图片宽度
     */
    private Integer picWidth;

    /**
     * 图片高度
     */
    private Integer picHeight;

    /**
     * 图片宽高比例
     */
    private Double picScale;

    /**
     * 图片格式
     */
    private String picFormat;

    /**
     * 文件 SHA-256（上传时对最终存储内容计算，用于同一用户的秒传）
     */
    private String fileHash;

    /**
     * 创建用户 id
     */
    private Long userId;

    /**
     * 创建时间
     */
    private Date createTime;

    /**
     * 编辑时间
     */
    private Date editTime;

    /**
     * 更新时间
     */
    private Date updateTime;

    /**
     * 是否删除
     */
    @TableLogic
    private Integer isDelete;

    /**
     * 移入回收站时间；未删除时为空
     */
    private Date deletedAt;

    /**
     * 删除操作人 id
     */
    private Long deletedBy;

    /**
     * 状态：0-待审核; 1-通过; 2-拒绝
     */
    private Integer reviewStatus;

    /**
     * 审核信息
     */
    private String reviewMessage;

    /**
     * 审核人 id
     */
    private Long reviewerId;

    /**
     * 审核时间
     */
    private Date reviewTime;

    /**
     * 机审状态：0-正常 1-违规 2-疑似；null 表示尚未机审
     */
    private Integer machineReviewStatus;

    /**
     * 机审命中标签（主标签/子标签）
     */
    private String machineReviewLabel;

    /**
     * 机审置信度 0-100
     */
    private Integer machineReviewScore;

    /**
     * 机审时间
     */
    private Date machineReviewTime;

    /**
     * 文审状态：0-正常 1-违规 2-疑似；null 表示尚未文审
     */
    private Integer textReviewStatus;

    /**
     * 文审命中标签（主标签/子标签）
     */
    private String textReviewLabel;

    /**
     * 文审置信度 0-100
     */
    private Integer textReviewScore;

    /**
     * 文审时间
     */
    private Date textReviewTime;



}
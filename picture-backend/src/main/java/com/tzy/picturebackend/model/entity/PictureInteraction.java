package com.tzy.picturebackend.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 图片互动（收藏 / 点赞）。
 * <p>
 * 纯关系表，不做逻辑删除：取消收藏直接物理删除关系行，避免唯一键
 * {@code uk_picture_user_type (pictureId, userId, type)} 与「软删除后重新收藏」冲突。
 * 计数列 {@code picture.favoriteCount} / {@code picture.likeCount} 是它的冗余汇总。
 */
@TableName("picture_interaction")
@Data
public class PictureInteraction implements Serializable {

    /**
     * id
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 图片 id
     */
    private Long pictureId;

    /**
     * 用户 id
     */
    private Long userId;

    /**
     * 互动类型：favorite / like
     */
    private String type;

    /**
     * 创建时间
     */
    private Date createTime;

    private static final long serialVersionUID = 1L;
}

package com.tzy.picturebackend.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 图片专辑
 *
 * @TableName album
 */
@TableName("album")
@Data
public class Album implements Serializable {

    /**
     * id
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 作者 id
     */
    private Long userId;

    /**
     * 专辑名称
     */
    private String name;

    /**
     * 简介
     */
    private String description;

    /**
     * 封面图片 id；空表示按最新一张公开图片回退
     */
    private Long coverPictureId;

    /**
     * 图片数量（作者视角，含未过审）
     */
    private Integer pictureCount;

    /**
     * 0-私密 1-公开
     */
    private Integer isPublic;

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

    private static final long serialVersionUID = 1L;
}

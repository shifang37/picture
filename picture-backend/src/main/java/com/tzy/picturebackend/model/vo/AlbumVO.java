package com.tzy.picturebackend.model.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 专辑列表项。
 */
@Data
public class AlbumVO implements Serializable {

    /**
     * id
     */
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
     * 封面图片 id
     */
    private Long coverPictureId;

    /**
     * 封面图片地址（已按可见性回退，可能为 null）
     */
    private String coverUrl;

    /**
     * 图片数量（作者视角，含未过审）
     */
    private Integer pictureCount;

    /**
     * 公开图片数量（仅已过审，避免外部推断未过审内容）
     */
    private Long publicCount;

    /**
     * 0-私密 1-公开
     */
    private Integer isPublic;

    /**
     * 创建时间
     */
    private Date createTime;

    /**
     * 更新时间
     */
    private Date updateTime;

    /**
     * 作者公开信息
     */
    private PublicUserVO user;

    private static final long serialVersionUID = 1L;
}

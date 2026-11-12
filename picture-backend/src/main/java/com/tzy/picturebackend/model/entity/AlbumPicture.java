package com.tzy.picturebackend.model.entity;

import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 专辑-图片关系。
 * <p>
 * 联合主键 {@code (albumId, pictureId)}，是纯关系表：不做逻辑删除，
 * 移除即物理删除；专辑被删除时关系一并清理，但图片本身不受影响。
 */
@Data
public class AlbumPicture implements Serializable {

    /**
     * 专辑 id
     */
    private Long albumId;

    /**
     * 图片 id
     */
    private Long pictureId;

    /**
     * 加入时间
     */
    private Date createTime;

    private static final long serialVersionUID = 1L;
}

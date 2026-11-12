package com.tzy.picturebackend.model.vo;

import lombok.Data;

import java.io.Serializable;

/**
 * 专辑的公开图片数量（仅 reviewStatus = 1）。
 * <p>
 * 用于批量填充专辑列表，避免为每个专辑单独 COUNT 造成 N+1。
 */
@Data
public class AlbumPictureCountVO implements Serializable {

    /**
     * 专辑 id
     */
    private Long albumId;

    /**
     * 公开图片数量
     */
    private Long publicCount;

    private static final long serialVersionUID = 1L;
}

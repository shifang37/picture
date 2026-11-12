package com.tzy.picturebackend.model.vo;

import com.tzy.picturebackend.model.entity.Picture;
import lombok.Data;

import java.io.Serializable;

/**
 * 某张图片的互动状态与计数。
 * <p>
 * 计数是 {@code picture} 上的冗余列，读接口返回实时值（详情/状态接口不走列表缓存）；
 * 未登录时 {@code favorite} / {@code like} 恒为 false，计数仍然返回。
 */
@Data
public class InteractionStatusVO implements Serializable {

    /**
     * 图片 id
     */
    private Long pictureId;

    /**
     * 当前用户是否已收藏；未登录恒为 false
     */
    private boolean favorite;

    /**
     * 当前用户是否已点赞；未登录恒为 false
     */
    private boolean like;

    /**
     * 收藏数
     */
    private Long favoriteCount;

    /**
     * 点赞数
     */
    private Long likeCount;

    private static final long serialVersionUID = 1L;

    /**
     * 从图片实体构造计数部分（不含选中状态）
     */
    public static InteractionStatusVO of(Picture picture) {
        InteractionStatusVO statusVO = new InteractionStatusVO();
        if (picture != null) {
            statusVO.setPictureId(picture.getId());
            statusVO.setFavoriteCount(picture.getFavoriteCount() == null ? 0L : picture.getFavoriteCount());
            statusVO.setLikeCount(picture.getLikeCount() == null ? 0L : picture.getLikeCount());
        }
        return statusVO;
    }
}

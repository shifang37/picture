package com.tzy.picturebackend.model.vo;

import lombok.Data;

import java.io.Serializable;

/**
 * 作者作品聚合统计。
 * <p>
 * 只统计「已通过审核且未删除」的图片，与作者主页展示的作品列表口径一致；
 * 作为 {@link PublicUserProfileVO} 的一部分返回，不单独暴露接口。
 */
@Data
public class AuthorStatsVO implements Serializable {

    /**
     * 公开作品数
     */
    private long pictureCount;

    /**
     * 作品总下载量
     */
    private long downloadCount;

    /**
     * 作品总收藏数
     */
    private long favoriteCount;

    /**
     * 作品总点赞数
     */
    private long likeCount;

    private static final long serialVersionUID = 1L;
}

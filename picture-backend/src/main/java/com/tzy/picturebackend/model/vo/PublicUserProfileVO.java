package com.tzy.picturebackend.model.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 公开作者主页信息。
 * <p>
 * 只包含展示所需的公开字段：账号、角色、密码状态等内部信息都不会出现；
 * 统计口径为「已通过审核且未删除」的作品，作者本人未过审的作品请到「我的图片」查看。
 */
@Data
public class PublicUserProfileVO implements Serializable {

    /**
     * 作者公开资料（昵称、头像、简介）
     */
    private PublicUserVO user;

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

    /**
     * 加入时间（用户创建时间）
     */
    private Date joinedAt;

    private static final long serialVersionUID = 1L;
}

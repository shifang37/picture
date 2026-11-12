package com.tzy.picturebackend.model.vo;

import lombok.Data;

import java.io.Serializable;

/**
 * 公开用户信息：只包含展示所需的字段。
 * <p>
 * 账号（userAccount）和角色（userRole）属于内部信息，不允许通过公开接口返回；
 * 管理端需要账号/角色时使用 {@link UserVO}。
 */
@Data
public class PublicUserVO implements Serializable {

    /**
     * 用户 id
     */
    private Long id;

    /**
     * 用户昵称
     */
    private String userName;

    /**
     * 用户头像
     */
    private String userAvatar;

    /**
     * 用户简介
     */
    private String userProfile;

    private static final long serialVersionUID = 1L;
}

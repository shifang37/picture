package com.tzy.picturebackend.model.dto.user;

import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 修改个人资料请求：只能修改当前登录用户的昵称、头像和简介
 */
@Data
public class UserUpdateProfileRequest implements Serializable {

    /**
     * 用户昵称
     */
    @Size(max = 256, message = "用户昵称不能超过 256 个字符")
    private String userName;

    /**
     * 用户头像
     */
    @Size(max = 1024, message = "用户头像地址不能超过 1024 个字符")
    private String userAvatar;

    /**
     * 用户简介
     */
    @Size(max = 512, message = "用户简介不能超过 512 个字符")
    private String userProfile;

    private static final long serialVersionUID = 1L;
}

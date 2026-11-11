package com.tzy.picturebackend.model.dto.user;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

@Data
public class UserAddRequest implements Serializable {

    /**
     * 用户昵称
     */
    @Size(max = 256, message = "用户昵称不能超过 256 个字符")
    private String userName;

    /**
     * 账号
     */
    @NotBlank(message = "账号不能为空")
    @Size(min = 4, max = 256, message = "账号长度需在 4 到 256 个字符之间")
    private String userAccount;

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

    /**
     * 用户角色: user, admin
     */
    @Pattern(regexp = "user|admin", message = "用户角色只能是 user 或 admin")
    private String userRole;

    private static final long serialVersionUID = 1L;
}

package com.tzy.picturebackend.model.dto.user;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

@Data
public class UserUpdateRequest implements Serializable {

    /**
     * id
     */
    @NotNull(message = "id 不能为空")
    @Positive(message = "id 必须为正数")
    private Long id;

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
     * 简介
     */
    @Size(max = 512, message = "用户简介不能超过 512 个字符")
    private String userProfile;

    /**
     * 用户角色：user/admin
     */
    @Pattern(regexp = "user|admin", message = "用户角色只能是 user 或 admin")
    private String userRole;

    private static final long serialVersionUID = 1L;
}

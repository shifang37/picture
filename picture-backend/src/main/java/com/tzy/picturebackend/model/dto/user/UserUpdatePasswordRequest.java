package com.tzy.picturebackend.model.dto.user;

import com.tzy.picturebackend.annotation.StrongPassword;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serializable;

/**
 * 修改密码请求
 */
@Data
public class UserUpdatePasswordRequest implements Serializable {

    /**
     * 原密码
     */
    @NotBlank(message = "原密码不能为空")
    private String oldPassword;

    /**
     * 新密码
     */
    @NotBlank(message = "新密码不能为空")
    @StrongPassword
    private String newPassword;

    private static final long serialVersionUID = 1L;
}

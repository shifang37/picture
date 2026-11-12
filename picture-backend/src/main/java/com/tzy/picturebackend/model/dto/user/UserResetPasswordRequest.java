package com.tzy.picturebackend.model.dto.user;

import com.tzy.picturebackend.annotation.StrongPassword;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.io.Serializable;

/**
 * 管理员重置用户密码请求
 */
@Data
public class UserResetPasswordRequest implements Serializable {

    /**
     * 目标用户 id
     */
    @NotNull(message = "用户 id 不能为空")
    @Positive(message = "用户 id 必须为正数")
    private Long id;

    /**
     * 新密码
     */
    @NotBlank(message = "新密码不能为空")
    @StrongPassword
    private String newPassword;

    private static final long serialVersionUID = 1L;
}

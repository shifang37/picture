package com.tzy.picturebackend.model.dto.user;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
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
    @Size(min = 6, max = 72, message = "新密码长度需在 6 到 72 个字符之间")
    private String newPassword;

    private static final long serialVersionUID = 1L;
}

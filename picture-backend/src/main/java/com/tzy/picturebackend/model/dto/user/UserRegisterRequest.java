package com.tzy.picturebackend.model.dto.user;

import com.tzy.picturebackend.annotation.StrongPassword;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

@Data
public class UserRegisterRequest implements Serializable {

    private static final long serialVersionUID = -8258749681798387255L;

    /**
     * 账号
     */
    @NotBlank(message = "账号不能为空")
    @Size(min = 4, max = 256, message = "账号长度需在 4 到 256 个字符之间")
    private String userAccount;

    /**
     * 密码
     */
    @NotBlank(message = "密码不能为空")
    @StrongPassword
    private String userPassword;

    /**
     * 确认密码
     */
    @NotBlank(message = "确认密码不能为空")
    private String checkPassword;

    /**
     * 图形验证码 id（由 /user/captcha 下发）
     */
    @NotBlank(message = "验证码不能为空")
    @Size(max = 64, message = "验证码标识不合法")
    private String captchaId;

    /**
     * 图形验证码
     */
    @NotBlank(message = "验证码不能为空")
    @Size(max = 16, message = "验证码不合法")
    private String captchaCode;
}

package com.tzy.picturebackend.model.dto.user;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

@Data
public class UserLoginRequest implements Serializable {

    private static final long serialVersionUID = -3667464197811641436L;

    @NotBlank(message = "账号不能为空")
    @Size(max = 256, message = "账号不能超过 256 个字符")
    private String userAccount;

    @NotBlank(message = "密码不能为空")
    @Size(min = 6, max = 72, message = "密码长度需在 6 到 72 个字符之间")
    private String userPassword;
}

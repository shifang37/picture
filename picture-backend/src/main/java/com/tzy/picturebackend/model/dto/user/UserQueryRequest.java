package com.tzy.picturebackend.model.dto.user;

import com.tzy.picturebackend.common.PageRequest;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;

@EqualsAndHashCode(callSuper = true)
@Data
public class UserQueryRequest extends PageRequest implements Serializable {

    /**
     * id
     */
    private Long id;

    /**
     * 用户昵称
     */
    @Size(max = 256, message = "用户昵称不能超过 256 个字符")
    private String userName;

    /**
     * 账号
     */
    @Size(max = 256, message = "账号不能超过 256 个字符")
    private String userAccount;

    /**
     * 简介
     */
    @Size(max = 512, message = "用户简介不能超过 512 个字符")
    private String userProfile;

    /**
     * 用户角色：user/admin/ban
     */
    @Size(max = 256, message = "用户角色不能超过 256 个字符")
    private String userRole;

    private static final long serialVersionUID = 1L;
}

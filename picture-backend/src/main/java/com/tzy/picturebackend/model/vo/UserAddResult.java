package com.tzy.picturebackend.model.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 管理员创建用户的结果。
 * <p>
 * 初始密码由服务端随机生成且只返回这一次，因此单独返回给管理员转达，
 * 不再使用全站统一的弱默认密码。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserAddResult implements Serializable {

    /**
     * 新用户 id
     */
    private Long id;

    /**
     * 初始密码（仅本次响应可见，用户首次登录后必须修改）
     */
    private String initialPassword;

    private static final long serialVersionUID = 1L;
}

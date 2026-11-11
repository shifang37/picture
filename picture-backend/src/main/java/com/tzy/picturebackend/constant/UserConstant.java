package com.tzy.picturebackend.constant;

public interface UserConstant {

    /**
     * 用户登录态键
     */
    String USER_LOGIN_STATE = "user_login";

    /**
     * 登录会话版本键：密码变更后旧版本会话失效
     */
    String USER_LOGIN_VERSION = "user_login_version";

    //  region 权限

    /**
     * 默认角色
     */
    String DEFAULT_ROLE = "user";

    /**
     * 管理员角色
     */
    String ADMIN_ROLE = "admin";

    // endregion
}

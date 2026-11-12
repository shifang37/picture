package com.tzy.picturebackend.constant;

public interface UserConstant {

    /**
     * 用户登录态键：Session 中只保存用户 id（Long），不再保存整个 User 实体
     */
    String USER_LOGIN_ID = "user_login_id";

    /**
     * 登录会话版本键：密码变更后旧版本会话失效
     */
    String USER_LOGIN_VERSION = "user_login_version";

    /**
     * 首次登录强制改密标记：Session 中保存 Boolean，为 true 时只允许改密、登出和读取登录态
     */
    String USER_PASSWORD_CHANGE_REQUIRED = "user_password_change_required";

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

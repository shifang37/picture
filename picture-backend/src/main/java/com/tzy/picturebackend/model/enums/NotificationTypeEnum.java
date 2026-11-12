package com.tzy.picturebackend.model.enums;

import lombok.Getter;

/**
 * 站内通知类型。
 * <p>
 * 只覆盖「用户确实需要知道」的事件：审核结论、账号安全、系统公告。
 * 收藏 / 点赞默认不通知，避免热门图片的作者被刷屏。
 */
@Getter
public enum NotificationTypeEnum {

    REVIEW_PASS("REVIEW_PASS", "图片审核通过"),

    REVIEW_REJECT("REVIEW_REJECT", "图片审核未通过"),

    PASSWORD_RESET("PASSWORD_RESET", "密码已被重置"),

    ROLE_CHANGED("ROLE_CHANGED", "账号角色已变更"),

    SYSTEM("SYSTEM", "系统公告");

    private final String value;

    /**
     * 默认标题；具体通知可以覆盖（例如公告使用管理员填写的标题）
     */
    private final String defaultTitle;

    NotificationTypeEnum(String value, String defaultTitle) {
        this.value = value;
        this.defaultTitle = defaultTitle;
    }

    /**
     * 按值查找枚举；非法值返回 null
     */
    public static NotificationTypeEnum getEnumByValue(String value) {
        for (NotificationTypeEnum typeEnum : values()) {
            if (typeEnum.value.equals(value)) {
                return typeEnum;
            }
        }
        return null;
    }
}

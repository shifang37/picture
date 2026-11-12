package com.tzy.picturebackend.model.enums;

import lombok.Getter;

/**
 * 图片互动类型。
 * <p>
 * {@code counterColumn} 直接对应 {@code picture} 表上的冗余计数列；取值来自枚举而不是客户端，
 * 因此可以安全地拼进 {@code UPDATE ... SET x = x + 1}，不会被注入。
 */
@Getter
public enum PictureInteractionTypeEnum {

    FAVORITE("favorite", "favoriteCount", "收藏"),

    LIKE("like", "likeCount", "点赞");

    private final String value;

    /**
     * 冗余计数列名
     */
    private final String counterColumn;

    /**
     * 中文名称（提示文案用）
     */
    private final String text;

    PictureInteractionTypeEnum(String value, String counterColumn, String text) {
        this.value = value;
        this.counterColumn = counterColumn;
        this.text = text;
    }

    /**
     * 按值查找枚举；非法值返回 null，由调用方转成 40000
     */
    public static PictureInteractionTypeEnum getEnumByValue(String value) {
        for (PictureInteractionTypeEnum typeEnum : values()) {
            if (typeEnum.value.equals(value)) {
                return typeEnum;
            }
        }
        return null;
    }
}

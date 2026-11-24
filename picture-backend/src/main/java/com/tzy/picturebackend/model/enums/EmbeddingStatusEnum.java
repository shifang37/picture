package com.tzy.picturebackend.model.enums;

import lombok.Getter;

/**
 * 图片向量化状态
 */
@Getter
public enum EmbeddingStatusEnum {

    PENDING("待处理", 0),
    SUCCESS("成功", 1),
    FAILED("失败", 2);

    private final String text;
    private final int value;

    EmbeddingStatusEnum(String text, int value) {
        this.text = text;
        this.value = value;
    }
}

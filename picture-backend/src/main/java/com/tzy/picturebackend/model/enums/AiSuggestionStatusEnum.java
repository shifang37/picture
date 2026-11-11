package com.tzy.picturebackend.model.enums;

import lombok.Getter;

/**
 * AI 建议生成状态
 */
@Getter
public enum AiSuggestionStatusEnum {

    PROCESSING("处理中", 0),
    SUCCESS("成功", 1),
    FAILED("失败", 2);

    private final String text;
    private final int value;

    AiSuggestionStatusEnum(String text, int value) {
        this.text = text;
        this.value = value;
    }
}

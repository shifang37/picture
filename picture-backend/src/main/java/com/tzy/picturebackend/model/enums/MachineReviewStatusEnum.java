package com.tzy.picturebackend.model.enums;

import cn.hutool.core.util.ObjUtil;
import lombok.Getter;

/**
 * 机审状态，与数据万象图片审核响应的 Result 字段一一对应。
 */
@Getter
public enum MachineReviewStatusEnum {

    NORMAL("正常", 0),
    VIOLATION("违规", 1),
    SUSPECT("疑似", 2);

    private final String text;
    private final int value;

    MachineReviewStatusEnum(String text, int value) {
        this.text = text;
        this.value = value;
    }

    /**
     * 根据 value 获取枚举
     */
    public static MachineReviewStatusEnum getEnumByValue(Integer value) {
        if (ObjUtil.isEmpty(value)) {
            return null;
        }
        for (MachineReviewStatusEnum statusEnum : MachineReviewStatusEnum.values()) {
            if (statusEnum.value == value) {
                return statusEnum;
            }
        }
        return null;
    }

    /**
     * 解析数据万象返回的 Result 字段：0 正常、1 违规、2 疑似。
     *
     * @param result 接口响应中的 Result
     * @return 对应枚举；空值或未知取值返回 null
     */
    public static MachineReviewStatusEnum fromResult(String result) {
        if (result == null || result.isBlank()) {
            return null;
        }
        try {
            return getEnumByValue(Integer.parseInt(result.trim()));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

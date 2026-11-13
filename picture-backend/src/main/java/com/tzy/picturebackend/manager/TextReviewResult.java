package com.tzy.picturebackend.manager;

import com.tzy.picturebackend.model.enums.TextReviewStatusEnum;

/**
 * 归一化后的文本审核结果。
 *
 * @param status 审核结论
 * @param label  命中标签（主标签/子标签），未命中时为 Unknown
 * @param score  置信度 0-100，接口未返回时为 null
 */
public record TextReviewResult(TextReviewStatusEnum status, String label, Integer score) {
}

package com.tzy.picturebackend.manager;

import com.tzy.picturebackend.model.enums.MachineReviewStatusEnum;

/**
 * 数据万象图片审核的归一化结果。
 *
 * @param status 机审状态：正常 / 违规 / 疑似
 * @param label  命中标签，主标签与子标签用 "/" 拼接
 * @param score  置信度 0-100，接口未返回时为 null
 */
public record MachineReviewResult(MachineReviewStatusEnum status, String label, Integer score) {
}

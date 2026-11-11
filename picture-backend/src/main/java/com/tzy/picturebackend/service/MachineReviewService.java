package com.tzy.picturebackend.service;

import com.tzy.picturebackend.model.entity.Picture;

/**
 * 机审初筛：调用数据万象审核图片，写回机审字段，并按配置的模式决定是否自动处置。
 */
public interface MachineReviewService {

    /**
     * 对一张图片执行机审。
     * <p>
     * 调用方负责确认图片仍处于待审核状态；本方法会在写回机审字段时做幂等判断，
     * 并通过带条件的更新保证不会覆盖后来完成的人工审核结论。
     *
     * @param picture 待审核图片
     */
    void review(Picture picture);
}

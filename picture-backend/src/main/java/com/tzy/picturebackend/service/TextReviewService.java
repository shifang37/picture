package com.tzy.picturebackend.service;

import com.tzy.picturebackend.model.entity.Picture;

/**
 * 文本审核（图片名称、简介）。
 */
public interface TextReviewService {

    /**
     * 审核一张图片的名称与简介，并把结论写入 textReview* 字段。
     * <p>
     * 幂等：textReviewStatus 已有值时直接跳过，重复投递不会重复处置。
     * 开关关闭或文本为空时不做任何事。
     *
     * @param picture 待审核图片（需带 id、名称与简介）
     */
    void review(Picture picture);
}

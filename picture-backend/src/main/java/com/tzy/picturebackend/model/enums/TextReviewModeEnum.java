package com.tzy.picturebackend.model.enums;

/**
 * 文本审核结果的效力模式。
 * <p>
 * 文本只覆盖名称与简介，正常文本没有「自动通过」的语义（图片本身仍需人工审核），
 * 因此不提供 auto 模式。
 */
public enum TextReviewModeEnum {

    /**
     * 只记录文审结果，审核仍完全由人工完成
     */
    ASSIST,

    /**
     * 文审违规自动拒绝，正常与疑似仍走人工
     */
    AUTO_REJECT
}

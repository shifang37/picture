package com.tzy.picturebackend.model.enums;

/**
 * 机审结果的效力模式。
 * <p>
 * 决策 D3：默认 {@link #ASSIST}，只把机审结果作为辅助标记，观察误判率后再考虑自动处置。
 */
public enum MachineReviewModeEnum {

    /**
     * 只记录机审结果，审核仍完全由人工完成
     */
    ASSIST,

    /**
     * 机审违规的自动拒绝，正常与疑似仍走人工
     */
    AUTO_REJECT,

    /**
     * 机审违规自动拒绝、正常自动通过，只有疑似走人工
     */
    AUTO
}

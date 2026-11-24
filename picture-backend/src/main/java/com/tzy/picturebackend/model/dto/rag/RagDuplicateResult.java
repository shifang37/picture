package com.tzy.picturebackend.model.dto.rag;

/**
 * 一次近重复检测的结果。
 *
 * @param status           MISS / SUSPECT / HIT / ERROR
 * @param similarPictureId 最相似的图片 id；MISS / ERROR 时为 null
 * @param cosine           与最相似图片的余弦相似度；MISS / ERROR 时为 0
 * @param notified         本次是否真的发出了站内通知（同一对图片第二次检测时为 false）
 */
public record RagDuplicateResult(Status status, Long similarPictureId, double cosine, boolean notified) {

    public enum Status {
        /**
         * 未达到疑似阈值
         */
        MISS,
        /**
         * 超过疑似阈值但未达到判重阈值：只记指标，不打扰作者
         */
        SUSPECT,
        /**
         * 超过判重阈值：命中；是否真的通知由 {@link #notified} 表示（同一对图片只提示一次）
         */
        HIT,
        /**
         * 向量查询失败；不影响图片本身
         */
        ERROR
    }

    public static RagDuplicateResult miss() {
        return new RagDuplicateResult(Status.MISS, null, 0, false);
    }

    public static RagDuplicateResult suspect(Long similarPictureId, double cosine) {
        return new RagDuplicateResult(Status.SUSPECT, similarPictureId, cosine, false);
    }

    public static RagDuplicateResult hit(Long similarPictureId, double cosine, boolean notified) {
        return new RagDuplicateResult(Status.HIT, similarPictureId, cosine, notified);
    }

    public static RagDuplicateResult error() {
        return new RagDuplicateResult(Status.ERROR, null, 0, false);
    }
}

package com.tzy.picturebackend.service;

import com.tzy.picturebackend.model.entity.PictureEmbedding;

import java.util.Collection;

/**
 * 图片向量化状态读写。
 * <p>
 * 这张表只是派生索引的去重缓存，不是事实源；所有写操作都按 pictureId 幂等，
 * 重复消息、重复重建不会产生多行。
 */
public interface RagEmbeddingStateService {

    /**
     * 读取一张图片的向量化状态；没有记录时返回 null。
     */
    PictureEmbedding get(Long pictureId);

    /**
     * 标记文本向量成功。
     */
    void markTextSuccess(Long pictureId, String hash, String model, String version);

    /**
     * 标记图片向量成功。
     */
    void markImageSuccess(Long pictureId, String hash, String model, String version);

    /**
     * 记录一次失败：只把本次尝试过的一侧置为失败，attemptCount 累加。
     *
     * @param textFailed  本次文本向量是否失败
     * @param imageFailed 本次图片向量是否失败
     * @param error       失败原因（超过 512 字符会被截断）
     */
    void markFailure(Long pictureId, boolean textFailed, boolean imageFailed, String error);

    /**
     * 删除单张图片的状态记录；记录不存在时静默返回。
     */
    void delete(Long pictureId);

    /**
     * 批量删除状态记录（物理清理、批量删除时使用）。
     */
    void deleteByPictureIds(Collection<Long> pictureIds);

    /**
     * 公开图片中文本向量尚未成功的数量。
     */
    long countPendingPublic();

    /**
     * 任一侧失败的图片数量。
     */
    long countFailed();

    /**
     * 文本向量已成功且当前公开的图片数量，用于巡检。
     */
    long countSuccessPublic();
}

package com.tzy.picturebackend.model.dto.rag;

/**
 * 一张候选图片与源图的余弦相似度。
 * <p>
 * Elasticsearch 的 kNN 分数经过归一化（cosine 相似度映射到 [0,1]，见
 * {@code ElasticsearchRagSearchService#cosineFromScore}），这里统一还原成 [-1,1] 的余弦值，
 * 与 {@code docs/rag/m4-calibration.md} 中的阈值口径一致。
 *
 * @param pictureId 候选图片 id
 * @param cosine    与源图的余弦相似度
 */
public record RagSimilarMatch(Long pictureId, double cosine) {
}

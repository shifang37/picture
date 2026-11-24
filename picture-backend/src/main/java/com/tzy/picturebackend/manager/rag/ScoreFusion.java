package com.tzy.picturebackend.manager.rag;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 多路检索结果的分数融合。
 * <p>
 * 采用 RRF（Reciprocal Rank Fusion）：{@code score = Σ 1 / (k + rank)}，只使用名次、不做分数归一化。
 * BM25 分值与余弦相似度量纲不同，跨查询归一化不稳定，而名次融合天然规避了这个问题。
 * <p>
 * 规则：
 * <ul>
 *   <li>同一路结果内的重复 id 只按第一次出现的名次计分，避免重复计权；</li>
 *   <li>同时出现在两路的 id 分数相加，因此会被排到单路命中之前；</li>
 *   <li>同分时按 id 升序，保证分页顺序稳定、可复现。</li>
 * </ul>
 * 纯函数，不依赖 ES 或 Spring，便于单测覆盖边界条件。
 */
public final class ScoreFusion {

    private ScoreFusion() {
    }

    /**
     * 融合两路召回结果。
     *
     * @param bm25Ids BM25 召回 id，按相关度降序；可为 null 或空
     * @param knnIds  kNN 召回 id，按相似度降序；可为 null 或空
     * @param k       RRF 平滑常数，规划取 60；小于 1 时按 1 处理
     * @return 按 RRF 分数降序、同分按 id 升序的并集
     */
    public static List<Long> rrf(List<Long> bm25Ids, List<Long> knnIds, int k) {
        int rankOffset = Math.max(1, k);
        Map<Long, Double> scores = new HashMap<>();
        addRankScores(scores, bm25Ids, rankOffset);
        addRankScores(scores, knnIds, rankOffset);
        if (scores.isEmpty()) {
            return List.of();
        }
        List<Map.Entry<Long, Double>> entries = new ArrayList<>(scores.entrySet());
        entries.sort((left, right) -> {
            int byScore = Double.compare(right.getValue(), left.getValue());
            return byScore != 0 ? byScore : Long.compare(left.getKey(), right.getKey());
        });
        List<Long> fused = new ArrayList<>(entries.size());
        for (Map.Entry<Long, Double> entry : entries) {
            fused.add(entry.getKey());
        }
        return fused;
    }

    /**
     * 把一路结果按名次累加进分数表；重复 id 只在第一次出现时计分。
     */
    private static void addRankScores(Map<Long, Double> scores, List<Long> ids, int rankOffset) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        Set<Long> seen = new HashSet<>(ids.size());
        int rank = 0;
        for (Long id : ids) {
            if (id == null || !seen.add(id)) {
                continue;
            }
            rank++;
            scores.merge(id, 1.0 / (rankOffset + rank), Double::sum);
        }
    }
}

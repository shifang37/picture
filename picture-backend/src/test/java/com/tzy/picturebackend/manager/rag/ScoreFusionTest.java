package com.tzy.picturebackend.manager.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RRF 融合：去重、双路加权、空路与同分稳定排序。
 */
class ScoreFusionTest {

    private static final int K = 60;

    @Test
    @DisplayName("两路都为空：返回空列表")
    void rrf_bothEmpty() {
        assertTrue(ScoreFusion.rrf(List.of(), List.of(), K).isEmpty());
        assertTrue(ScoreFusion.rrf(null, null, K).isEmpty());
    }

    @Test
    @DisplayName("只有一路：保持原顺序")
    void rrf_singlePathKeepsOrder() {
        assertEquals(List.of(3L, 1L, 2L), ScoreFusion.rrf(List.of(3L, 1L, 2L), List.of(), K));
        assertEquals(List.of(3L, 1L, 2L), ScoreFusion.rrf(List.of(), List.of(3L, 1L, 2L), K));
    }

    @Test
    @DisplayName("双路命中：分数相加，排在单路命中之前")
    void rrf_overlapScoresAddUp() {
        // 20 在 BM25 第 2、kNN 第 1；10 在 BM25 第 1；30 在 kNN 第 2。
        // 20: 1/62 + 1/61 > 10: 1/61 > 30: 1/62
        List<Long> fused = ScoreFusion.rrf(List.of(10L, 20L), List.of(20L, 30L), K);
        assertEquals(List.of(20L, 10L, 30L), fused);
    }

    @Test
    @DisplayName("同分：按 id 升序，保证分页稳定")
    void rrf_tiesBreakByIdAscending() {
        // 两路都只有一个元素且名次相同，分数完全相等
        assertEquals(List.of(5L, 7L, 9L), ScoreFusion.rrf(List.of(5L, 9L), List.of(7L), K));
    }

    @Test
    @DisplayName("一路内重复 id：只按第一次出现的名次计分，不重复加权")
    void rrf_deduplicatesWithinOnePath() {
        // 1 第一次在第 1 名，1 第二次不应再计分；2 是第 2 名
        assertEquals(List.of(1L, 2L), ScoreFusion.rrf(List.of(1L, 1L, 2L), List.of(), K));
    }

    @Test
    @DisplayName("k 小于 1 时按 1 处理，不产生除零")
    void rrf_invalidKIsClamped() {
        assertEquals(List.of(1L, 2L), ScoreFusion.rrf(List.of(1L), List.of(2L), 0));
    }

    @Test
    @DisplayName("同一对输入多次调用结果一致（无随机与哈希顺序依赖）")
    void rrf_isDeterministic() {
        List<Long> bm25 = List.of(4L, 2L, 8L, 6L);
        List<Long> knn = List.of(6L, 4L, 9L);
        assertEquals(ScoreFusion.rrf(bm25, knn, K), ScoreFusion.rrf(bm25, knn, K));
    }
}

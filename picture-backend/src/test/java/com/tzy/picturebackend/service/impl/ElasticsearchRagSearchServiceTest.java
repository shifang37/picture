package com.tzy.picturebackend.service.impl;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tzy.picturebackend.config.RagProperties;
import com.tzy.picturebackend.manager.RagSearchCache;
import com.tzy.picturebackend.manager.rag.EmbeddingClient;
import com.tzy.picturebackend.manager.rag.EmbeddingException;
import com.tzy.picturebackend.manager.rag.RecordingEmbeddingClient;
import com.tzy.picturebackend.metrics.PictureMetrics;
import com.tzy.picturebackend.model.dto.picture.PictureQueryRequest;
import com.tzy.picturebackend.model.dto.rag.RagIdPage;
import com.tzy.picturebackend.model.dto.rag.RagSearchResult;
import com.tzy.picturebackend.service.PictureSearchService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 混合检索核心逻辑：RRF 融合、分页切片、查询向量缓存、降级与 filter 一致性。
 * <p>
 * ElasticsearchClient 用 Mockito 替代，不连接真实 ES；向量由 {@link RecordingEmbeddingClient}
 * 生成（确定性、不联网）。
 */
class ElasticsearchRagSearchServiceTest {

    private ElasticsearchClient elasticsearchClient;

    private RecordingEmbeddingClient embeddingClient;

    private PictureSearchService pictureSearchService;

    private RagSearchCache ragSearchCache;

    private SimpleMeterRegistry meterRegistry;

    private PictureMetrics pictureMetrics;

    private RagProperties ragProperties;

    private ElasticsearchRagSearchService service;

    @BeforeEach
    void setUp() {
        elasticsearchClient = mock(ElasticsearchClient.class);
        embeddingClient = new RecordingEmbeddingClient();
        pictureSearchService = mock(PictureSearchService.class);
        ragSearchCache = mock(RagSearchCache.class);
        meterRegistry = new SimpleMeterRegistry();
        pictureMetrics = new PictureMetrics(meterRegistry);
        ragProperties = new RagProperties();
        service = newService(embeddingClient);
    }

    @Test
    @DisplayName("混合检索：两路结果按 RRF 融合，按请求分页并返回并集 total")
    void hybrid_fusesBothPaths() throws Exception {
        when(pictureSearchService.searchPictureIds(any())).thenReturn(page(List.of(1L, 2L, 3L), 100));
        respondWith(List.of(3L, 4L));

        RagSearchResult result = service.hybrid(request(1, 10, "日落海边"));

        // 3 同时命中两路排第一；2 与 4 同分，按 id 升序
        assertEquals(List.of(3L, 1L, 2L, 4L), result.ids().getRecords());
        assertEquals(4, result.ids().getTotal(), "total 取两路并集大小");
        assertEquals("hybrid", result.mode());
        assertNull(result.degradedReason());
        assertEquals(1, embeddingClient.textBatches().size());
        verify(ragSearchCache).put(eq("hybrid"), any(), any(RagIdPage.class));
    }

    @Test
    @DisplayName("混合检索：kNN filter 与 BM25 对齐（reviewStatus/category/tags/userId）")
    void hybrid_buildsFilterForVectorIndex() throws Exception {
        when(pictureSearchService.searchPictureIds(any())).thenReturn(page(List.of(1L), 10));
        respondWith(List.of(1L));
        PictureQueryRequest request = request(1, 10, "雪山");
        request.setCategory("风景");
        request.setTags(List.of("山", "雪"));
        request.setUserId(5L);

        service.hybrid(request);

        ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(elasticsearchClient).search(captor.capture(), eq(Void.class));
        var knn = captor.getValue().knn().get(0);
        assertEquals("captionVector", knn.field());
        assertEquals(50, knn.k());
        assertEquals(200, knn.numCandidates());
        List<String> fields = knn.filter().stream()
                .flatMap(query -> query.bool().filter().stream())
                .map(query -> query.term().field())
                .toList();
        assertTrue(fields.containsAll(List.of("reviewStatus", "category", "tags", "userId")),
                "实际 filter 字段: " + fields);
        var reviewStatus = knn.filter().stream()
                .flatMap(query -> query.bool().filter().stream())
                .filter(query -> "reviewStatus".equals(query.term().field()))
                .findFirst().orElseThrow();
        assertEquals(1L, reviewStatus.term().value().longValue());
    }

    @Test
    @DisplayName("混合检索：同一查询第二次命中查询向量缓存，不再调用 embedding")
    void hybrid_cachesQueryVector() throws Exception {
        when(pictureSearchService.searchPictureIds(any())).thenReturn(page(List.of(1L), 10));
        respondWith(List.of(1L));

        service.hybrid(request(1, 10, "暖色调的日落"));
        service.hybrid(request(1, 10, "  暖色调的日落  "));

        assertEquals(1, embeddingClient.textBatches().size(), "归一化后同一查询应复用查询向量");
    }

    @Test
    @DisplayName("混合检索：向量化失败回落到 BM25，并标记 embed_error")
    void hybrid_fallsBackWhenEmbeddingFails() throws Exception {
        service = newService(failingEmbedding("boom"));
        when(pictureSearchService.searchPictureIds(any())).thenReturn(page(List.of(7L, 8L), 100));

        RagSearchResult result = service.hybrid(request(1, 10, "日落"));

        assertEquals(List.of(7L, 8L), result.ids().getRecords());
        assertEquals(100, result.ids().getTotal(), "降级后 total 仍取 BM25 命中总数");
        assertEquals("es", result.mode());
        assertEquals("embed_error", result.degradedReason());
        verify(elasticsearchClient, never()).search(any(SearchRequest.class), any(Class.class));
        assertEquals(1.0, degradedCount("embed_error"));
    }

    @Test
    @DisplayName("混合检索：向量化超时单独标记 embed_timeout")
    void hybrid_marksTimeoutReason() {
        service = newService(failingEmbedding("request timed out"));
        when(pictureSearchService.searchPictureIds(any())).thenReturn(page(List.of(1L), 1));

        RagSearchResult result = service.hybrid(request(1, 10, "日落"));

        assertEquals("embed_timeout", result.degradedReason());
        assertEquals(1.0, degradedCount("embed_timeout"));
    }

    @Test
    @DisplayName("混合检索：kNN 异常回落到 BM25，并标记 es_error")
    void hybrid_fallsBackWhenKnnFails() throws Exception {
        when(pictureSearchService.searchPictureIds(any())).thenReturn(page(List.of(3L, 1L), 2));
        when(elasticsearchClient.search(any(SearchRequest.class), eq(Void.class)))
                .thenThrow(new IOException("knn down"));

        RagSearchResult result = service.hybrid(request(1, 10, "日落"));

        assertEquals(List.of(3L, 1L), result.ids().getRecords());
        assertEquals("es_error", result.degradedReason());
        assertEquals(1.0, degradedCount("es_error"));
    }

    @Test
    @DisplayName("混合检索：向量索引不支持的过滤条件整体回落 BM25，不返回不满足条件的结果")
    void hybrid_fallsBackOnUnsupportedFilter() throws Exception {
        PictureQueryRequest request = request(1, 10, "日落");
        request.setPicFormat("webp");
        when(pictureSearchService.searchPictureIds(any())).thenReturn(page(List.of(9L), 1));

        RagSearchResult result = service.hybrid(request);

        assertEquals("unsupported_filter", result.degradedReason());
        assertEquals(List.of(9L), result.ids().getRecords());
        assertTrue(embeddingClient.textBatches().isEmpty());
        verify(elasticsearchClient, never()).search(any(SearchRequest.class), any(Class.class));
    }

    @Test
    @DisplayName("混合检索：分页深度超过融合窗口时回落 BM25")
    void hybrid_fallsBackOnDeepPage() throws Exception {
        when(pictureSearchService.searchPictureIds(any())).thenReturn(page(List.of(), 0));

        RagSearchResult result = service.hybrid(request(30, 20, "日落"));

        assertEquals("deep_page", result.degradedReason());
        verify(elasticsearchClient, never()).search(any(SearchRequest.class), any(Class.class));
    }

    @Test
    @DisplayName("语义检索：只走 kNN，不改用 BM25")
    void semantic_usesKnnOnly() throws Exception {
        respondWith(List.of(5L, 6L));

        RagSearchResult result = service.semantic(request(1, 10, "孤独的山"));

        assertEquals(List.of(5L, 6L), result.ids().getRecords());
        assertEquals(2, result.ids().getTotal());
        assertEquals("semantic", result.mode());
        verifyNoInteractions(pictureSearchService);
        verify(ragSearchCache).put(eq("semantic"), any(), any(RagIdPage.class));
    }

    @Test
    @DisplayName("结果缓存命中：不再向量化、不查 ES")
    void hybrid_usesResultCache() {
        RagIdPage cached = new RagIdPage();
        cached.setIds(List.of(9L));
        cached.setTotal(1);
        cached.setCurrent(1);
        cached.setSize(10);
        when(ragSearchCache.get(eq("hybrid"), any())).thenReturn(cached);

        RagSearchResult result = service.hybrid(request(1, 10, "日落"));

        assertEquals(List.of(9L), result.ids().getRecords());
        assertEquals("hybrid", result.mode());
        assertFalse(result.degraded());
        assertTrue(embeddingClient.textBatches().isEmpty());
        verifyNoInteractions(pictureSearchService);
    }

    @Test
    @DisplayName("零结果不写结果缓存：新图过审后不必等 TTL")
    void hybrid_doesNotCacheEmptyResult() throws Exception {
        when(pictureSearchService.searchPictureIds(any())).thenReturn(page(List.of(), 0));
        respondWith(List.of());

        RagSearchResult result = service.hybrid(request(1, 10, "不存在的词"));

        assertEquals(0, result.ids().getTotal());
        verify(ragSearchCache, never()).put(any(), any(), any());
    }

    @Test
    @DisplayName("查询词归一化：去首尾空白并压缩连续空白")
    void normalizeQuery_collapsesWhitespace() {
        assertEquals("日落 海边", ElasticsearchRagSearchService.normalizeQuery("  日落   海边 "));
        assertEquals("", ElasticsearchRagSearchService.normalizeQuery(null));
    }

    private ElasticsearchRagSearchService newService(EmbeddingClient client) {
        return new ElasticsearchRagSearchService(elasticsearchClient, client,
                pictureSearchService, ragProperties, ragSearchCache, pictureMetrics, new ObjectMapper());
    }

    private void respondWith(List<Long> ids) throws IOException {
        when(elasticsearchClient.search(any(SearchRequest.class), eq(Void.class)))
                .thenReturn(response(ids));
    }

    private static SearchResponse<Void> response(List<Long> ids) {
        List<Hit<Void>> hits = ids.stream()
                .map(id -> Hit.<Void>of(hit -> hit
                        .index("picture_vec")
                        .id(String.valueOf(id))
                        .score(0.9)))
                .toList();
        return SearchResponse.of(response -> response
                .took(1)
                .timedOut(false)
                .shards(shards -> shards.total(1).successful(1).failed(0))
                .hits(metadata -> metadata.hits(hits)));
    }

    private EmbeddingClient failingEmbedding(String message) {
        EmbeddingClient client = mock(EmbeddingClient.class);
        when(client.model()).thenReturn("failing-model");
        when(client.version()).thenReturn("v1");
        when(client.dims()).thenReturn(1024);
        when(client.embedQuery(any())).thenThrow(EmbeddingException.permanent(message));
        return client;
    }

    private PictureQueryRequest request(int current, int pageSize, String searchText) {
        PictureQueryRequest request = new PictureQueryRequest();
        request.setCurrent(current);
        request.setPageSize(pageSize);
        request.setSearchText(searchText);
        request.setSearchMode("hybrid");
        return request;
    }

    private static Page<Long> page(List<Long> ids, long total) {
        Page<Long> page = new Page<>(1, 50, total);
        page.setRecords(ids);
        return page;
    }

    private double degradedCount(String reason) {
        Counter counter = meterRegistry.find(PictureMetrics.RAG_DEGRADED).tag("reason", reason).counter();
        return counter == null ? 0.0 : counter.count();
    }
}

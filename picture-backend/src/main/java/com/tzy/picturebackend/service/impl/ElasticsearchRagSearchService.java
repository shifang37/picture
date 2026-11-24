package com.tzy.picturebackend.service.impl;

import cn.hutool.core.util.StrUtil;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.tzy.picturebackend.config.RagProperties;
import com.tzy.picturebackend.manager.RagSearchCache;
import com.tzy.picturebackend.manager.rag.EmbeddingClient;
import com.tzy.picturebackend.manager.rag.EmbeddingException;
import com.tzy.picturebackend.manager.rag.ScoreFusion;
import com.tzy.picturebackend.metrics.PictureMetrics;
import com.tzy.picturebackend.model.dto.picture.PictureQueryRequest;
import com.tzy.picturebackend.model.dto.rag.RagIdPage;
import com.tzy.picturebackend.model.dto.rag.RagSearchResult;
import com.tzy.picturebackend.model.enums.PictureReviewStatusEnum;
import com.tzy.picturebackend.model.enums.PictureSearchModeEnum;
import com.tzy.picturebackend.service.PictureSearchService;
import com.tzy.picturebackend.service.RagSearchService;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 混合 / 语义检索的 Elasticsearch 实现。
 * <p>
 * 与 M2 的向量索引共用别名 {@code picture_vec}，与 BM25 索引完全隔离：
 * <ul>
 *   <li>BM25 侧复用 {@link PictureSearchService#searchPictureIds}（即
 *       {@code PictureSearchQueryBuilder} 的全部过滤与排序语义），取 topN；</li>
 *   <li>kNN 侧把查询词向量化后查 {@code captionVector}，filter 只带向量索引里有的字段
 *       （{@code reviewStatus}/{@code category}/{@code tags}/{@code userId}），与 BM25 侧保持一致；</li>
 *   <li>两路结果用 RRF 融合，按请求分页；total 取两路并集大小（仅用于展示）；</li>
 *   <li>查询向量进 Caffeine（TTL 10 分钟），检索结果进独立的 Redis 缓存（TTL 5 分钟，
 *       不进公开列表缓存，避免与 keyword 结果串用）。</li>
 * </ul>
 * 降级：向量化超时/失败回落到 BM25（{@code embed_timeout}/{@code embed_error}），
 * kNN 异常回落到 BM25（{@code es_error}）；BM25 也失败时抛异常，由控制层继续回落 MySQL。
 */
@Slf4j
public class ElasticsearchRagSearchService implements RagSearchService {

    /**
     * 文本向量字段（与 M2 的索引映射一致）
     */
    static final String CAPTION_VECTOR_FIELD = "captionVector";

    /**
     * 查询向量本地缓存的最大条数
     */
    static final int QUERY_CACHE_MAX_SIZE = 1000;

    private final ElasticsearchClient elasticsearchClient;

    private final EmbeddingClient embeddingClient;

    private final PictureSearchService pictureSearchService;

    private final RagProperties ragProperties;

    private final RagSearchCache ragSearchCache;

    private final PictureMetrics pictureMetrics;

    private final ObjectMapper objectMapper;

    private final Cache<String, float[]> queryVectorCache;

    public ElasticsearchRagSearchService(ElasticsearchClient elasticsearchClient,
                                         EmbeddingClient embeddingClient,
                                         PictureSearchService pictureSearchService,
                                         RagProperties ragProperties,
                                         RagSearchCache ragSearchCache,
                                         PictureMetrics pictureMetrics,
                                         ObjectMapper objectMapper) {
        this.elasticsearchClient = elasticsearchClient;
        this.embeddingClient = embeddingClient;
        this.pictureSearchService = pictureSearchService;
        this.ragProperties = ragProperties;
        this.ragSearchCache = ragSearchCache;
        this.pictureMetrics = pictureMetrics;
        this.objectMapper = objectMapper;
        this.queryVectorCache = Caffeine.newBuilder()
                .maximumSize(QUERY_CACHE_MAX_SIZE)
                .expireAfterWrite(Duration.ofSeconds(
                        Math.max(1, ragProperties.getSearch().getQueryCacheSeconds())))
                .recordStats()
                .build();
        // 查询向量缓存命中率进指标：cache_gets_total{cache="picture.rag.query",result=hit|miss}
        pictureMetrics.registerCaffeineCache("picture.rag.query", queryVectorCache);
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public RagSearchResult hybrid(PictureQueryRequest request) {
        return search(request, PictureSearchModeEnum.HYBRID);
    }

    @Override
    public RagSearchResult semantic(PictureQueryRequest request) {
        return search(request, PictureSearchModeEnum.SEMANTIC);
    }

    private RagSearchResult search(PictureQueryRequest request, PictureSearchModeEnum mode) {
        long started = System.nanoTime();
        String modeValue = mode.getValue();
        try {
            String queryText = normalizeQuery(request.getSearchText());
            if (queryText.isEmpty()) {
                // 控制层已把空搜索词归为 keyword，这里只做防御
                return degraded(keywordPage(request), "empty_query");
            }
            if (hasUnsupportedFilter(request)) {
                log.warn("检索条件超出向量索引字段，回落 BM25, mode={}, searchText={}", modeValue, queryText);
                return degraded(keywordPage(request), "unsupported_filter");
            }
            long from = from(request);
            long end = from + request.getPageSize();
            int maxTop = Math.max(1, ragProperties.getSearch().getMaxTop());
            if (end > maxTop) {
                log.warn("分页深度超过融合窗口 {}, 回落 BM25, mode={}, current={}",
                        maxTop, modeValue, request.getCurrent());
                return degraded(keywordPage(request), "deep_page");
            }
            RagIdPage cached = ragSearchCache.get(modeValue, request);
            if (cached != null) {
                return new RagSearchResult(cached.toPage(), modeValue, null);
            }

            int topN = topN(request, mode);
            Page<Long> result;
            if (mode == PictureSearchModeEnum.HYBRID) {
                // BM25 先跑：它失败时直接抛出，由控制层回落 MySQL（与 keyword 路径一致）
                Page<Long> bm25Page = bm25Top(request, topN);
                float[] vector;
                try {
                    vector = queryVector(queryText);
                } catch (RuntimeException e) {
                    log.warn("查询向量化失败，回落 BM25, mode={}, searchText={}", modeValue, queryText, e);
                    return degraded(pageFrom(bm25Page, request), embedFailureReason(e));
                }
                List<Long> knnIds;
                try {
                    knnIds = knnIds(vector, request, topN);
                } catch (RuntimeException e) {
                    log.warn("向量检索失败，回落 BM25, mode={}, searchText={}", modeValue, queryText, e);
                    return degraded(pageFrom(bm25Page, request), "es_error");
                }
                List<Long> fused = ScoreFusion.rrf(bm25Page.getRecords(), knnIds, rrfK());
                result = pageOf(slice(fused, from, request.getPageSize()), fused.size(), request);
            } else {
                float[] vector;
                try {
                    vector = queryVector(queryText);
                } catch (RuntimeException e) {
                    log.warn("查询向量化失败，回落 BM25, mode={}, searchText={}", modeValue, queryText, e);
                    return degraded(keywordPage(request), embedFailureReason(e));
                }
                List<Long> knnIds;
                try {
                    knnIds = knnIds(vector, request, topN);
                } catch (RuntimeException e) {
                    log.warn("向量检索失败，回落 BM25, mode={}, searchText={}", modeValue, queryText, e);
                    return degraded(keywordPage(request), "es_error");
                }
                result = pageOf(slice(knnIds, from, request.getPageSize()), knnIds.size(), request);
            }
            if (result.getTotal() > 0) {
                // 不缓存零结果：新图过审后最多等一个 TTL 就能被召回
                ragSearchCache.put(modeValue, request, RagIdPage.of(result));
            }
            return new RagSearchResult(result, modeValue, null);
        } finally {
            pictureMetrics.ragSearch(modeValue, System.nanoTime() - started);
        }
    }

    /**
     * 查询词归一化：去首尾空白、压缩连续空白，保证同一句话命中同一个缓存 key
     */
    static String normalizeQuery(String text) {
        if (text == null) {
            return "";
        }
        return text.trim().replaceAll("\\s+", " ");
    }

    /**
     * 向量索引只存了 reviewStatus / category / tags / userId，其它精确条件无法在 kNN 侧等价表达；
     * 与其返回不满足条件的结果，不如整体回落 BM25。排序字段不在此列：有搜索词时 BM25 本来就按相关度排序。
     */
    private static boolean hasUnsupportedFilter(PictureQueryRequest request) {
        return request.getId() != null
                || StrUtil.isNotBlank(request.getName())
                || StrUtil.isNotBlank(request.getIntroduction())
                || StrUtil.isNotBlank(request.getPicFormat())
                || request.getPicSize() != null
                || request.getPicWidth() != null
                || request.getPicHeight() != null
                || request.getPicScale() != null;
    }

    private long from(PictureQueryRequest request) {
        return Math.max(0L, ((long) request.getCurrent() - 1) * request.getPageSize());
    }

    /**
     * 融合窗口：至少覆盖请求的当前页，且不小于配置的单路 topN
     */
    private int topN(PictureQueryRequest request, PictureSearchModeEnum mode) {
        long end = from(request) + request.getPageSize();
        int configured = mode == PictureSearchModeEnum.HYBRID
                ? Math.max(ragProperties.getSearch().getBm25Top(), ragProperties.getSearch().getKnnTop())
                : ragProperties.getSearch().getKnnTop();
        long top = Math.max(end, Math.max(1, configured));
        return (int) Math.min(top, Integer.MAX_VALUE);
    }

    private int rrfK() {
        return Math.max(1, ragProperties.getSearch().getRrfK());
    }

    /**
     * BM25 侧：复制一份请求把分页改成 1/topN，完全复用现有查询构造器与过滤条件
     */
    private Page<Long> bm25Top(PictureQueryRequest request, int topN) {
        PictureQueryRequest topRequest = objectMapper.convertValue(request, PictureQueryRequest.class);
        topRequest.setCurrent(1);
        topRequest.setPageSize(topN);
        topRequest.setSearchMode(null);
        return pictureSearchService.searchPictureIds(topRequest);
    }

    /**
     * 按请求的分页信息返回 BM25 结果（降级路径，不额外查一次 ES）
     */
    private Page<Long> pageFrom(Page<Long> topPage, PictureQueryRequest request) {
        List<Long> records = slice(topPage.getRecords(), from(request), request.getPageSize());
        return pageOf(records, topPage.getTotal(), request);
    }

    private Page<Long> keywordPage(PictureQueryRequest request) {
        return pictureSearchService.searchPictureIds(request);
    }

    private Page<Long> pageOf(List<Long> ids, long total, PictureQueryRequest request) {
        Page<Long> page = new Page<>(request.getCurrent(), request.getPageSize(), total);
        page.setRecords(ids);
        return page;
    }

    private static List<Long> slice(List<Long> ids, long from, int size) {
        if (ids == null || ids.isEmpty() || size <= 0) {
            return List.of();
        }
        int start = (int) Math.min(from, ids.size());
        int stop = (int) Math.min(from + size, ids.size());
        if (start >= stop) {
            return List.of();
        }
        return new ArrayList<>(ids.subList(start, stop));
    }

    /**
     * 查询向量：Caffeine 缓存 key 含模型与版本，模型升级后不会误用旧向量
     */
    private float[] queryVector(String text) {
        String key = embeddingClient.model() + ":" + embeddingClient.version() + ":" + text;
        return queryVectorCache.get(key, ignored -> embedQuery(text));
    }

    private float[] embedQuery(String text) {
        // 查询链路：单次调用、短超时、不重试（见 EmbeddingClient#embedQuery）
        float[] vector = embeddingClient.embedQuery(text);
        int dims = ragProperties.getIndex().getDims();
        if (vector == null || vector.length != dims) {
            throw EmbeddingException.permanent("查询向量维度不合法: expected=" + dims
                    + ", actual=" + (vector == null ? "null" : vector.length));
        }
        for (float value : vector) {
            if (!Float.isFinite(value)) {
                throw EmbeddingException.permanent("查询向量包含 NaN/Infinity");
            }
        }
        return vector;
    }

    /**
     * kNN 侧：只召回归一化文本向量最相近的 topN，过滤条件与 BM25 侧保持一致
     */
    private List<Long> knnIds(float[] vector, PictureQueryRequest request, int topN) {
        List<Float> queryVector = new ArrayList<>(vector.length);
        for (float value : vector) {
            queryVector.add(value);
        }
        Query filter = Query.of(q -> q.bool(b -> b.filter(knnFilters(request))));
        int numCandidates = Math.max(ragProperties.getSearch().getNumCandidates(), topN);
        SearchRequest searchRequest = new SearchRequest.Builder()
                .index(ragProperties.getIndex().getAlias())
                .size(topN)
                .source(source -> source.fetch(false))
                .knn(knn -> knn
                        .field(CAPTION_VECTOR_FIELD)
                        .queryVector(queryVector)
                        .k(topN)
                        .numCandidates(numCandidates)
                        .filter(filter))
                .build();
        SearchResponse<Void> response;
        try {
            response = elasticsearchClient.search(searchRequest, Void.class);
        } catch (IOException e) {
            throw new IllegalStateException("执行向量检索失败", e);
        }
        List<Long> ids = new ArrayList<>(response.hits().hits().size());
        for (Hit<Void> hit : response.hits().hits()) {
            Long id = parseId(hit.id());
            if (id != null) {
                ids.add(id);
            }
        }
        return ids;
    }

    /**
     * 与 BM25 的 filter 对齐：审核状态恒为通过，category / tags / userId 逐个精确匹配
     */
    private List<Query> knnFilters(PictureQueryRequest request) {
        List<Query> filters = new ArrayList<>();
        filters.add(termQuery("reviewStatus", PictureReviewStatusEnum.PASS.getValue()));
        if (StrUtil.isNotBlank(request.getCategory())) {
            filters.add(termQuery("category", request.getCategory().trim()));
        }
        if (request.getUserId() != null) {
            filters.add(termQuery("userId", request.getUserId()));
        }
        if (request.getTags() != null) {
            for (String tag : request.getTags()) {
                if (StrUtil.isNotBlank(tag)) {
                    filters.add(termQuery("tags", tag.trim()));
                }
            }
        }
        return filters;
    }

    private static Query termQuery(String field, String value) {
        return Query.of(q -> q.term(t -> t.field(field).value(value)));
    }

    private static Query termQuery(String field, Long value) {
        return Query.of(q -> q.term(t -> t.field(field).value(value)));
    }

    private static Query termQuery(String field, Integer value) {
        return Query.of(q -> q.term(t -> t.field(field).value(value)));
    }

    private RagSearchResult degraded(Page<Long> page, String reason) {
        pictureMetrics.ragDegraded(reason);
        return new RagSearchResult(page, RagSearchResult.MODE_ES, reason);
    }

    private static String embedFailureReason(RuntimeException e) {
        String message = StrUtil.nullToEmpty(e.getMessage()).toLowerCase(Locale.ROOT);
        return message.contains("timeout") || message.contains("timed out") || message.contains("超时")
                ? "embed_timeout"
                : "embed_error";
    }

    private static Long parseId(String id) {
        if (StrUtil.isBlank(id)) {
            return null;
        }
        try {
            return Long.valueOf(id);
        } catch (NumberFormatException e) {
            log.warn("向量索引中存在非法图片 id, id={}", id);
            return null;
        }
    }
}

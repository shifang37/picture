package com.tzy.picturebackend.service;

import com.tzy.picturebackend.model.dto.picture.PictureQueryRequest;
import com.tzy.picturebackend.model.dto.rag.RagSearchResult;

/**
 * 混合 / 语义检索。
 * <p>
 * 只负责召回一页图片 id；MySQL 仍是唯一事实来源，调用方拿到 id 后再回表、过滤
 * {@code reviewStatus=1} 并转 VO。未启用 RAG 或搜索后端不是 Elasticsearch 时注入
 * {@code NoopRagSearchService}，{@link #enabled()} 为 false，控制器按 keyword 处理。
 * <p>
 * 降级约定：查询向量化失败或 kNN 异常时，服务内部回落到 BM25 并在结果里标记
 * {@code degradedReason}，不会抛 5xx；只有 BM25 也失败才抛异常，由控制层继续回落 MySQL。
 */
public interface RagSearchService {

    /**
     * 混合 / 语义检索是否真正可用（RAG=true 且 search.engine=es）
     */
    boolean enabled();

    /**
     * BM25 + kNN 各取 topN，RRF 融合后按请求分页
     */
    RagSearchResult hybrid(PictureQueryRequest request);

    /**
     * 纯 kNN 召回（以文搜图 / 调试对比）
     */
    RagSearchResult semantic(PictureQueryRequest request);
}

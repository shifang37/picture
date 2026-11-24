package com.tzy.picturebackend.service.impl;

import com.tzy.picturebackend.model.dto.picture.PictureQueryRequest;
import com.tzy.picturebackend.model.dto.rag.RagSearchResult;
import com.tzy.picturebackend.service.RagSearchService;

/**
 * 未启用 RAG 时的空实现：不连接 Elasticsearch、不调用 embedding。
 * <p>
 * 控制器在 {@link #enabled()} 为 false 时把 {@code hybrid}/{@code semantic} 请求
 * 按 {@code keyword} 处理，因此这些方法只做防御性报错，正常不会被调用。
 */
public class NoopRagSearchService implements RagSearchService {

    @Override
    public boolean enabled() {
        return false;
    }

    @Override
    public RagSearchResult hybrid(PictureQueryRequest request) {
        throw new UnsupportedOperationException("未启用 RAG 混合检索");
    }

    @Override
    public RagSearchResult semantic(PictureQueryRequest request) {
        throw new UnsupportedOperationException("未启用 RAG 语义检索");
    }
}

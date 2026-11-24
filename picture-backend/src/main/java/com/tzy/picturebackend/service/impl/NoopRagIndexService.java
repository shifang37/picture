package com.tzy.picturebackend.service.impl;

import com.tzy.picturebackend.model.entity.Picture;
import com.tzy.picturebackend.model.vo.rag.RagReindexVO;
import com.tzy.picturebackend.service.RagIndexService;

/**
 * 未启用 RAG 或搜索后端不是 Elasticsearch 时的空实现。
 * <p>
 * 所有操作都是 no-op，不连接 ES、不调用 embedding、不写状态表；
 * 消费者据此跳过整条链路，行为与接入 RAG 前完全一致。
 */
public class NoopRagIndexService implements RagIndexService {

    @Override
    public boolean enabled() {
        return false;
    }

    @Override
    public void indexPicture(Picture picture) {
        // 未启用 RAG：不维护向量索引
    }

    @Override
    public void deletePicture(Long pictureId) {
        // 未启用 RAG：不维护向量索引
    }

    @Override
    public long countIndexed() {
        return 0L;
    }

    @Override
    public RagReindexVO reindexAll() {
        return new RagReindexVO();
    }

    @Override
    public String indexName() {
        return null;
    }

    @Override
    public boolean stale() {
        return false;
    }
}

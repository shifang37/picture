package com.tzy.picturebackend.service.impl;

import com.tzy.picturebackend.model.dto.rag.RagDuplicateResult;
import com.tzy.picturebackend.model.entity.Picture;
import com.tzy.picturebackend.service.RagDuplicateService;

/**
 * 未启用 RAG 或搜索后端不是 Elasticsearch 时的空实现。
 * <p>
 * 不查向量索引、不写 Redis、不发通知，消费者据此跳过整条近重复链路。
 */
public class NoopRagDuplicateService implements RagDuplicateService {

    @Override
    public boolean enabled() {
        return false;
    }

    @Override
    public RagDuplicateResult check(Picture picture) {
        return RagDuplicateResult.miss();
    }
}

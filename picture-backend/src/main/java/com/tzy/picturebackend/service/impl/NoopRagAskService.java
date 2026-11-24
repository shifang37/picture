package com.tzy.picturebackend.service.impl;

import com.tzy.picturebackend.model.dto.rag.RagAskRequest;
import com.tzy.picturebackend.model.vo.rag.RagAskResponse;
import com.tzy.picturebackend.service.RagAskService;

/**
 * 未启用 RAG（或搜索后端不是 Elasticsearch）时的空实现。
 * <p>
 * 不连接 ES、不调用 embedding / DeepSeek，也不读写 Redis：直接返回固定的降级文案，
 * 前端可以安全地展示「问答暂不可用」，而不是 5xx。
 */
public class NoopRagAskService implements RagAskService {

    @Override
    public boolean enabled() {
        return false;
    }

    @Override
    public RagAskResponse ask(RagAskRequest request, Long userId) {
        return RagAskResponse.degraded();
    }
}

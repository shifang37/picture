package com.tzy.picturebackend.service;

import com.tzy.picturebackend.model.dto.rag.RagAskRequest;
import com.tzy.picturebackend.model.vo.rag.RagAskResponse;

/**
 * 图库问答：只基于公开图片的检索结果生成带引用的回答。
 * <p>
 * 硬约束（代码强制，不依赖提示词）：
 * <ol>
 *   <li>引用 id 必须来自本次召回集，否则整条回答降级为拒答；</li>
 *   <li>召回集为空时不调用生成模型，直接返回固定拒答文案；</li>
 *   <li>检索或生成失败时返回 {@code degraded=true} + 固定文案，HTTP 仍为 200。</li>
 * </ol>
 * 未启用 RAG 或搜索后端不是 Elasticsearch 时注入 {@code NoopRagAskService}，
 * 不会产生任何外部调用。
 */
public interface RagAskService {

    /**
     * 问答是否真正可用（RAG=true 且 search.engine=es）
     */
    boolean enabled();

    /**
     * 回答一个问题。
     *
     * @param request 问题与可选 topK
     * @param userId  当前登录用户 id（每日限额按用户计）
     * @return 正常回答 / 拒答 / 降级三态
     * @throws com.tzy.picturebackend.exception.BusinessException 参数非法（40000）或超限（42900）
     */
    RagAskResponse ask(RagAskRequest request, Long userId);
}

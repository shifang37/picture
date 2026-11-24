package com.tzy.picturebackend.service;

import com.tzy.picturebackend.model.dto.rag.RagDuplicateResult;
import com.tzy.picturebackend.model.entity.Picture;

/**
 * 近重复检测与提示。
 * <p>
 * 在图片通过审核、写入图片向量之后触发：用同一作者 + 已过审的过滤条件做 top-1 相似度查询，
 * 命中判重阈值时给作者发一条站内通知。**只提示，不自动拒绝、不自动删除**；
 * 同一对图片只通知一次。
 * <p>
 * 未启用 RAG 或搜索后端不是 Elasticsearch 时注入空实现，{@link #enabled()} 为 false。
 */
public interface RagDuplicateService {

    /**
     * 近重复检测是否可用（RAG=true 且 search.engine=es）
     */
    boolean enabled();

    /**
     * 检测一张已过审图片是否与同一作者的其他公开图片高度相似，必要时通知作者。
     * <p>
     * 任何异常都只记录日志并返回 {@link RagDuplicateResult#error()}，绝不影响图片本身的索引与审核流程。
     *
     * @param picture 刚完成向量写入的公开图片
     */
    RagDuplicateResult check(Picture picture);
}

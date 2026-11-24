package com.tzy.picturebackend.service;

import com.tzy.picturebackend.model.entity.Picture;
import com.tzy.picturebackend.model.vo.rag.RagReindexVO;

/**
 * 图片向量索引维护。
 * <p>
 * 与 {@code PictureSearchService} 同构：MySQL 是唯一事实来源，索引里只保存已通过审核的公开图片；
 * 写入、删除、查询都走别名，全量重建先写临时索引再原子切换别名。
 * <p>
 * 未启用 RAG 或搜索后端不是 Elasticsearch 时注入空实现，所有方法都是 no-op。
 */
public interface RagIndexService {

    /**
     * 向量索引是否真正可用（RAG=true 且 search.engine=es）
     */
    boolean enabled();

    /**
     * 增量写入一张图片：按 hash 跳过未变化的向量，可公开时写索引，不可公开时删除文档。
     * <p>
     * 单条失败会写入 {@code picture_embedding.lastError} 后抛出，由消费者记录并确认消息。
     */
    void indexPicture(Picture picture);

    /**
     * 删除向量文档与向量化状态；文档或状态不存在时静默忽略。
     */
    void deletePicture(Long pictureId);

    /**
     * 当前向量索引中的文档数
     */
    long countIndexed();

    /**
     * 从 MySQL 全量重建向量索引；失败数大于 0 时不切换别名，旧索引保持可用。
     */
    RagReindexVO reindexAll();

    /**
     * 当前别名指向的索引名；尚未建索引时返回配置的索引名
     */
    String indexName();

    /**
     * 别名是否指向了不兼容的索引（不是 picture_vec_v* 或维度不一致）
     */
    boolean stale();
}

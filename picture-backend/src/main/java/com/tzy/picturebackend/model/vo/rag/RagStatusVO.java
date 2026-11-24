package com.tzy.picturebackend.model.vo.rag;

import lombok.Data;

/**
 * RAG 索引状态。
 * <p>
 * M1 只有开关与模型信息；M2 补充索引文档数、待处理/失败数与别名健康状态。
 */
@Data
public class RagStatusVO {

    /**
     * 是否启用 RAG
     */
    private boolean enabled;

    /**
     * 文本向量模型
     */
    private String textModel;

    /**
     * 图片向量模型
     */
    private String imageModel;

    /**
     * 向量维度
     */
    private int dims;

    /**
     * 当前别名指向的索引名；尚未建索引时为配置的初始名
     */
    private String indexName;

    /**
     * 对外读写使用的别名
     */
    private String alias;

    /**
     * 向量版本（图片/文本模型版本）
     */
    private String version;

    /**
     * 语料规则版本
     */
    private String textVersion;

    /**
     * 向量索引中的文档数
     */
    private long docCount;

    /**
     * 公开图片中文本向量尚未成功的数量
     */
    private long pendingCount;

    /**
     * 任一侧向量化失败的图片数
     */
    private long failedCount;

    /**
     * 别名是否指向不兼容的索引（不是 picture_vec_v* 或维度不一致）
     */
    private boolean stale;
}

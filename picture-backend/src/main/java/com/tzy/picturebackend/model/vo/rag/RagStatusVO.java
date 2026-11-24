package com.tzy.picturebackend.model.vo.rag;

import lombok.Data;

/**
 * RAG 状态（M1 只暴露开关与模型信息；M2 会补充索引文档数、待处理数等字段）。
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
     * 向量索引名
     */
    private String indexName;

    /**
     * 向量版本
     */
    private String version;
}

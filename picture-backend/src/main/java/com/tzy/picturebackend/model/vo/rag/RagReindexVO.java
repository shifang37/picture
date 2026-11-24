package com.tzy.picturebackend.model.vo.rag;

import lombok.Data;

/**
 * 全量重建向量索引的结果。
 */
@Data
public class RagReindexVO {

    /**
     * 写入新索引的图片数
     */
    private int indexed;

    /**
     * 跳过文本向量的图片数（语料为空）
     */
    private int skipped;

    /**
     * 向量化或写入失败的图片数
     */
    private int failed;

    /**
     * 是否已把别名切到新索引；失败数大于 0 时为 false，旧索引保持可用
     */
    private boolean switched;

    /**
     * 结果说明，便于管理端直接展示
     */
    private String message;
}

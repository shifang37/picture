package com.tzy.picturebackend.manager.rag;

import java.util.List;

/**
 * 向量化客户端。
 * <p>
 * 关闭 RAG 时注入 {@link NoopEmbeddingClient}，任何方法都会抛出永久错误，
 * 保证 {@code app.rag.enabled=false} 时不会产生外部调用。
 */
public interface EmbeddingClient {

    /**
     * 把一批文本转成向量。
     *
     * @param texts 文本列表，不能为 null；空列表返回空结果
     * @return 与输入顺序一致的 L2 归一化向量
     */
    List<float[]> embedTexts(List<String> texts);

    /**
     * 把一批图片转成向量。
     *
     * @param imageInputs 图片地址或 {@code data:image/...;base64,...} 数据 URL
     * @return 与输入顺序一致的 L2 归一化向量
     */
    List<float[]> embedImages(List<String> imageInputs);

    /**
     * 主模型标识（文本模型），用于状态页与落表
     */
    String model();

    /**
     * 向量版本；维度或模型变化时必须升级，旧向量随之重建
     */
    String version();

    /**
     * 向量维度（文本与图片必须一致）
     */
    int dims();
}

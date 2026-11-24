package com.tzy.picturebackend.manager.rag;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 未启用 RAG 时的空实现。
 * <p>
 * 不构造 HTTP 客户端、不读取 Key；任何向量化调用都会抛出永久错误，
 * 便于在误调用时快速暴露问题，而不是静默返回空向量。
 */
@Component
@ConditionalOnProperty(prefix = "app.rag", name = "enabled", havingValue = "false", matchIfMissing = true)
public class NoopEmbeddingClient implements EmbeddingClient {

    @Override
    public List<float[]> embedTexts(List<String> texts) {
        throw EmbeddingException.permanent("未启用 RAG，无法向量化文本");
    }

    @Override
    public List<float[]> embedImages(List<String> imageInputs) {
        throw EmbeddingException.permanent("未启用 RAG，无法向量化图片");
    }

    @Override
    public String model() {
        return "none";
    }

    @Override
    public String version() {
        return "0";
    }

    @Override
    public int dims() {
        return 0;
    }
}

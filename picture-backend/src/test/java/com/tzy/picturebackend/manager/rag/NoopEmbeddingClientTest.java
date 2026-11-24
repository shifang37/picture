package com.tzy.picturebackend.manager.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 未启用 RAG 时的空实现：不产生外部调用，误调用要快速失败。
 */
class NoopEmbeddingClientTest {

    private final NoopEmbeddingClient client = new NoopEmbeddingClient();

    @Test
    @DisplayName("文本向量化：直接抛永久错误")
    void embedTexts_throwsPermanent() {
        EmbeddingException exception = assertThrows(EmbeddingException.class,
                () -> client.embedTexts(List.of("海边日落")));

        assertFalse(exception.isRetryable());
        assertEquals("未启用 RAG，无法向量化文本", exception.getMessage());
    }

    @Test
    @DisplayName("图片向量化：直接抛永久错误")
    void embedImages_throwsPermanent() {
        EmbeddingException exception = assertThrows(EmbeddingException.class,
                () -> client.embedImages(List.of("https://example.com/a.jpg")));

        assertFalse(exception.isRetryable());
        assertEquals("未启用 RAG，无法向量化图片", exception.getMessage());
    }

    @Test
    @DisplayName("元信息：返回 none / 0，避免被误当成可用客户端")
    void metadata_isEmpty() {
        assertEquals("none", client.model());
        assertEquals("0", client.version());
        assertEquals(0, client.dims());
    }
}

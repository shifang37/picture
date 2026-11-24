package com.tzy.picturebackend.manager.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tzy.picturebackend.config.RagProperties;
import com.tzy.picturebackend.metrics.PictureMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 手动真实调用百炼（不进 CI）。
 * <p>
 * 运行方式：设置 {@code RAG_MANUAL_TEST=true} 与 {@code DASHSCOPE_API_KEY} 后
 * {@code .\mvnw.cmd test -Dtest=DashScopeEmbeddingClientManualTest}。
 * 只打印维度与模型，不打印 Key。
 */
@EnabledIfEnvironmentVariable(named = "RAG_MANUAL_TEST", matches = "true")
class DashScopeEmbeddingClientManualTest {

    @Test
    @DisplayName("真实调用：文本向量 dims=1024")
    void realTextEmbedding() {
        String apiKey = System.getenv("DASHSCOPE_API_KEY");
        assertNotNull(apiKey, "需要环境变量 DASHSCOPE_API_KEY");

        RagProperties properties = new RagProperties();
        properties.setEnabled(true);
        properties.getEmbedding().setApiKey(apiKey);
        String baseUrl = System.getenv("RAG_EMBEDDING_BASE_URL");
        if (baseUrl != null && !baseUrl.isBlank()) {
            properties.getEmbedding().setBaseUrl(baseUrl);
        }
        DashScopeEmbeddingClient client = new DashScopeEmbeddingClient(properties,
                new ObjectMapper(), new PictureMetrics(new SimpleMeterRegistry()));

        List<float[]> vectors = client.embedTexts(List.of("海边日落，暖色调，适合做海报"));

        assertEquals(1, vectors.size());
        float[] vector = vectors.get(0);
        assertEquals(1024, vector.length);
        System.out.println("MANUAL_EMBED dims=" + vector.length
                + " model=" + client.model()
                + " version=" + client.version()
                + " first=" + vector[0]);
    }
}

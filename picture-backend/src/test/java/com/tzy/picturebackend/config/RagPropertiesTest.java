package com.tzy.picturebackend.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RAG 配置绑定：默认全关，所有嵌套项都有可用默认值。
 */
class RagPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig.class);

    @Test
    @DisplayName("默认值：关闭、1024 维、批 10、30 秒超时")
    void defaults() {
        runner.run(context -> {
            RagProperties properties = context.getBean(RagProperties.class);

            assertFalse(properties.isEnabled());
            assertEquals("https://dashscope.aliyuncs.com", properties.getEmbedding().getBaseUrl());
            assertEquals("text-embedding-v4", properties.getEmbedding().getTextModel());
            assertEquals(1024, properties.getEmbedding().getTextDim());
            assertEquals("multimodal-embedding-v1", properties.getEmbedding().getImageModel());
            assertEquals(1024, properties.getEmbedding().getImageDim());
            assertEquals(10, properties.getEmbedding().getBatchSize());
            assertEquals(Duration.ofSeconds(30), properties.getEmbedding().getTimeout());
            assertEquals(2, properties.getEmbedding().getMaxRetries());
            assertEquals("picture_vec", properties.getIndex().getAlias());
            assertEquals("picture_vec_v1", properties.getIndex().getName());
            assertEquals(60, properties.getSearch().getRrfK());
            assertEquals(10, properties.getAsk().getDailyLimit());
            assertEquals(0.97, properties.getAsk().getSuspectThreshold(), 1e-9);
            assertEquals(0.985, properties.getAsk().getDuplicateThreshold(), 1e-9);
        });
    }

    @Test
    @DisplayName("覆盖：环境变量/本地配置可改开关、批大小、超时与阈值")
    void bindsOverrides() {
        runner.withPropertyValues(
                        "app.rag.enabled=true",
                        "app.rag.embedding.batch-size=5",
                        "app.rag.embedding.timeout=3s",
                        "app.rag.index.dims=768",
                        "app.rag.ask.duplicate-threshold=0.99")
                .run(context -> {
                    RagProperties properties = context.getBean(RagProperties.class);

                    assertTrue(properties.isEnabled());
                    assertEquals(5, properties.getEmbedding().getBatchSize());
                    assertEquals(Duration.ofSeconds(3), properties.getEmbedding().getTimeout());
                    assertEquals(768, properties.getIndex().getDims());
                    assertEquals(0.99, properties.getAsk().getDuplicateThreshold(), 1e-9);
                });
    }

    @Configuration
    @EnableConfigurationProperties(RagProperties.class)
    static class TestConfig {
    }
}

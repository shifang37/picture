package com.tzy.picturebackend.manager.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tzy.picturebackend.config.RagProperties;
import com.tzy.picturebackend.metrics.PictureMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bean 装配：开关关闭时只注入空实现，开启时才注入百炼实现。
 */
class RagEmbeddingWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ImportConfig.class)
            .withBean(RagProperties.class)
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withBean(PictureMetrics.class, () -> new PictureMetrics(new SimpleMeterRegistry()));

    @Test
    @DisplayName("默认关闭：EmbeddingClient 是 NoopEmbeddingClient")
    void disabledWiresNoopClient() {
        runner.run(context -> assertThat(context.getBean(EmbeddingClient.class))
                .isInstanceOf(NoopEmbeddingClient.class));
    }

    @Test
    @DisplayName("app.rag.enabled=true：EmbeddingClient 是 DashScopeEmbeddingClient")
    void enabledWiresDashScopeClient() {
        runner.withPropertyValues("app.rag.enabled=true")
                .run(context -> assertThat(context.getBean(EmbeddingClient.class))
                        .isInstanceOf(DashScopeEmbeddingClient.class));
    }

    @Configuration
    @Import({NoopEmbeddingClient.class, DashScopeEmbeddingClient.class})
    static class ImportConfig {
    }
}

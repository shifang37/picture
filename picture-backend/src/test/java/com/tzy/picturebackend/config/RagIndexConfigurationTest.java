package com.tzy.picturebackend.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.tzy.picturebackend.manager.rag.EmbeddingClient;
import com.tzy.picturebackend.manager.rag.EmbeddingText;
import com.tzy.picturebackend.manager.rag.RagImageLoader;
import com.tzy.picturebackend.metrics.PictureMetrics;
import com.tzy.picturebackend.service.PictureService;
import com.tzy.picturebackend.service.RagEmbeddingStateService;
import com.tzy.picturebackend.service.RagIndexService;
import com.tzy.picturebackend.service.impl.ElasticsearchRagIndexService;
import com.tzy.picturebackend.service.impl.NoopRagIndexService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 向量索引装配：只有「RAG 开启 + 搜索走 ES」才装配真实实现，其余组合都是空实现。
 */
class RagIndexConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig.class)
            .withBean(ElasticsearchClient.class, () -> mock(ElasticsearchClient.class))
            .withBean(EmbeddingClient.class, () -> mock(EmbeddingClient.class))
            .withBean(RagImageLoader.class, () -> mock(RagImageLoader.class))
            .withBean(RagEmbeddingStateService.class, () -> mock(RagEmbeddingStateService.class))
            .withBean(PictureService.class, () -> mock(PictureService.class))
            .withBean(PictureMetrics.class, () -> new PictureMetrics(new SimpleMeterRegistry()));

    @Test
    @DisplayName("默认关闭：注入 NoopRagIndexService")
    void disabledWiresNoop() {
        runner.run(context -> {
            RagIndexService service = context.getBean(RagIndexService.class);
            assertThat(service).isInstanceOf(NoopRagIndexService.class);
            assertThat(service.enabled()).isFalse();
        });
    }

    @Test
    @DisplayName("RAG 开启但搜索仍是 mysql：降级为空实现")
    void ragEnabledWithMysqlWiresNoop() {
        runner.withPropertyValues("app.rag.enabled=true", "app.search.engine=mysql")
                .run(context -> assertThat(context.getBean(RagIndexService.class))
                        .isInstanceOf(NoopRagIndexService.class));
    }

    @Test
    @DisplayName("RAG 开启且搜索走 ES：注入 Elasticsearch 实现")
    void ragEnabledWithEsWiresElasticsearch() {
        runner.withPropertyValues("app.rag.enabled=true", "app.search.engine=es")
                .run(context -> assertThat(context.getBean(RagIndexService.class))
                        .isInstanceOf(ElasticsearchRagIndexService.class));
    }

    @Configuration
    @EnableConfigurationProperties(RagProperties.class)
    @Import({RagIndexConfiguration.class, EmbeddingText.class})
    static class TestConfig {
    }
}

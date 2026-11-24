package com.tzy.picturebackend.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tzy.picturebackend.manager.RagSearchCache;
import com.tzy.picturebackend.manager.rag.EmbeddingClient;
import com.tzy.picturebackend.metrics.PictureMetrics;
import com.tzy.picturebackend.service.PictureSearchService;
import com.tzy.picturebackend.service.RagSearchService;
import com.tzy.picturebackend.service.impl.ElasticsearchRagSearchService;
import com.tzy.picturebackend.service.impl.NoopRagSearchService;
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
 * 混合检索装配：只有「RAG 开启 + 搜索走 ES」才装配真实实现，其余组合都是空实现。
 */
class RagSearchConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig.class)
            .withBean(ElasticsearchClient.class, () -> mock(ElasticsearchClient.class))
            .withBean(EmbeddingClient.class, () -> mock(EmbeddingClient.class))
            .withBean(PictureSearchService.class, () -> mock(PictureSearchService.class))
            .withBean(RagSearchCache.class, () -> mock(RagSearchCache.class))
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withBean(PictureMetrics.class, () -> new PictureMetrics(new SimpleMeterRegistry()));

    @Test
    @DisplayName("默认关闭：注入 NoopRagSearchService")
    void disabledWiresNoop() {
        runner.run(context -> {
            RagSearchService service = context.getBean(RagSearchService.class);
            assertThat(service).isInstanceOf(NoopRagSearchService.class);
            assertThat(service.enabled()).isFalse();
        });
    }

    @Test
    @DisplayName("RAG 开启但搜索仍是 mysql：降级为空实现")
    void ragEnabledWithMysqlWiresNoop() {
        runner.withPropertyValues("app.rag.enabled=true", "app.search.engine=mysql")
                .run(context -> assertThat(context.getBean(RagSearchService.class))
                        .isInstanceOf(NoopRagSearchService.class));
    }

    @Test
    @DisplayName("RAG 开启且搜索走 ES：注入 Elasticsearch 实现")
    void ragEnabledWithEsWiresElasticsearch() {
        runner.withPropertyValues("app.rag.enabled=true", "app.search.engine=es")
                .run(context -> assertThat(context.getBean(RagSearchService.class))
                        .isInstanceOf(ElasticsearchRagSearchService.class));
    }

    @Configuration
    @EnableConfigurationProperties(RagProperties.class)
    @Import(RagSearchConfiguration.class)
    static class TestConfig {
    }
}

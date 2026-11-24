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
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

/**
 * 混合检索 Bean 装配。
 * <p>
 * 与 {@link RagIndexConfiguration} 同一条件：只有 {@code app.rag.enabled=true} 且
 * {@code app.search.engine=es} 时装配真实实现；其它组合注入空实现，
 * 控制器据此把 hybrid / semantic 请求按 keyword 处理，不产生任何外部调用。
 */
@Configuration(proxyBeanMethods = false)
public class RagSearchConfiguration {

    @Bean
    @Conditional(RagVectorEnabledCondition.class)
    public RagSearchService elasticsearchRagSearchService(ElasticsearchClient elasticsearchClient,
                                                          EmbeddingClient embeddingClient,
                                                          PictureSearchService pictureSearchService,
                                                          RagProperties ragProperties,
                                                          RagSearchCache ragSearchCache,
                                                          PictureMetrics pictureMetrics,
                                                          ObjectMapper objectMapper) {
        return new ElasticsearchRagSearchService(elasticsearchClient, embeddingClient,
                pictureSearchService, ragProperties, ragSearchCache, pictureMetrics, objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean(RagSearchService.class)
    public RagSearchService noopRagSearchService() {
        return new NoopRagSearchService();
    }
}

package com.tzy.picturebackend.config;

import com.tzy.picturebackend.manager.rag.EmbeddingClient;
import com.tzy.picturebackend.manager.rag.EmbeddingText;
import com.tzy.picturebackend.manager.rag.RagImageLoader;
import com.tzy.picturebackend.metrics.PictureMetrics;
import com.tzy.picturebackend.service.PictureService;
import com.tzy.picturebackend.service.RagEmbeddingStateService;
import com.tzy.picturebackend.service.RagIndexService;
import com.tzy.picturebackend.service.impl.ElasticsearchRagIndexService;
import com.tzy.picturebackend.service.impl.NoopRagIndexService;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

/**
 * 向量索引 Bean 装配。
 * <p>
 * 只有 {@code app.rag.enabled=true} 且 {@code app.search.engine=es} 时才装配 Elasticsearch 实现；
 * 其他组合注入空实现，消费者会整体跳过，不产生任何外部调用。
 * <p>
 * 两个 Bean 方法顺序不能调换：{@code @ConditionalOnMissingBean} 依赖前一个方法先注册定义。
 */
@Configuration(proxyBeanMethods = false)
public class RagIndexConfiguration {

    @Bean
    @Conditional(RagVectorEnabledCondition.class)
    public RagIndexService elasticsearchRagIndexService(ElasticsearchClient elasticsearchClient,
                                                        EmbeddingClient embeddingClient,
                                                        EmbeddingText embeddingText,
                                                        RagProperties ragProperties,
                                                        RagEmbeddingStateService ragEmbeddingStateService,
                                                        RagImageLoader ragImageLoader,
                                                        PictureService pictureService,
                                                        PictureMetrics pictureMetrics) {
        return new ElasticsearchRagIndexService(elasticsearchClient, embeddingClient, embeddingText,
                ragProperties, ragEmbeddingStateService, ragImageLoader, pictureService, pictureMetrics);
    }

    @Bean
    @ConditionalOnMissingBean(RagIndexService.class)
    public RagIndexService noopRagIndexService() {
        return new NoopRagIndexService();
    }
}

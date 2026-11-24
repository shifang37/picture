package com.tzy.picturebackend.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tzy.picturebackend.manager.RagAskCache;
import com.tzy.picturebackend.manager.rag.DeepSeekRagAnswerGenerator;
import com.tzy.picturebackend.manager.rag.RagAnswerGenerator;
import com.tzy.picturebackend.manager.rag.RagAskPromptBuilder;
import com.tzy.picturebackend.metrics.PictureMetrics;
import com.tzy.picturebackend.service.PictureService;
import com.tzy.picturebackend.service.RagAskService;
import com.tzy.picturebackend.service.RagIndexService;
import com.tzy.picturebackend.service.RagSearchService;
import com.tzy.picturebackend.service.impl.NoopRagAskService;
import com.tzy.picturebackend.service.impl.RagAskServiceImpl;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 图库问答 Bean 装配。
 * <p>
 * 与 M2 / M3 / M4 同一条件：只有 {@code app.rag.enabled=true} 且
 * {@code app.search.engine=es} 时装配真实实现；其它组合注入空实现，
 * 控制器不会触发任何 ES / Redis / DeepSeek 调用。
 * <p>
 * 两个 Bean 方法顺序不能调换：{@code @ConditionalOnMissingBean} 依赖前一个方法先注册定义。
 */
@Configuration(proxyBeanMethods = false)
public class RagAskConfiguration {

    @Bean
    @Conditional(RagVectorEnabledCondition.class)
    public RagAskService ragAskService(RagSearchService ragSearchService,
                                       PictureService pictureService,
                                       RagIndexService ragIndexService,
                                       StringRedisTemplate redisTemplate,
                                       RagProperties ragProperties,
                                       AiProperties aiProperties,
                                       ObjectMapper objectMapper,
                                       PictureMetrics pictureMetrics) {
        RagAnswerGenerator generator = new DeepSeekRagAnswerGenerator(
                aiProperties, ragProperties, objectMapper, pictureMetrics);
        RagAskCache cache = new RagAskCache(redisTemplate, objectMapper, ragProperties);
        return new RagAskServiceImpl(ragSearchService, pictureService, ragIndexService,
                generator, new RagAskPromptBuilder(), cache, redisTemplate,
                ragProperties, pictureMetrics);
    }

    @Bean
    @ConditionalOnMissingBean(RagAskService.class)
    public RagAskService noopRagAskService() {
        return new NoopRagAskService();
    }
}

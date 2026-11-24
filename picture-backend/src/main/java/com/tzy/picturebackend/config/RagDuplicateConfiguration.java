package com.tzy.picturebackend.config;

import com.tzy.picturebackend.event.NotificationEventPublisher;
import com.tzy.picturebackend.metrics.PictureMetrics;
import com.tzy.picturebackend.service.PictureService;
import com.tzy.picturebackend.service.RagDuplicateService;
import com.tzy.picturebackend.service.RagSearchService;
import com.tzy.picturebackend.service.impl.NoopRagDuplicateService;
import com.tzy.picturebackend.service.impl.RagDuplicateServiceImpl;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 近重复检测 Bean 装配。
 * <p>
 * 与 {@link RagSearchConfiguration} 同一条件：只有 {@code app.rag.enabled=true} 且
 * {@code app.search.engine=es} 时装配真实实现；其它组合注入空实现，
 * 消费者不会触发任何 ES / Redis / 通知调用。
 * <p>
 * 两个 Bean 方法顺序不能调换：{@code @ConditionalOnMissingBean} 依赖前一个方法先注册定义。
 */
@Configuration(proxyBeanMethods = false)
public class RagDuplicateConfiguration {

    @Bean
    @Conditional(RagVectorEnabledCondition.class)
    public RagDuplicateService ragDuplicateService(RagSearchService ragSearchService,
                                                   PictureService pictureService,
                                                   NotificationEventPublisher notificationEventPublisher,
                                                   StringRedisTemplate redisTemplate,
                                                   RagProperties ragProperties,
                                                   PictureMetrics pictureMetrics) {
        return new RagDuplicateServiceImpl(ragSearchService, pictureService,
                notificationEventPublisher, redisTemplate, ragProperties, pictureMetrics);
    }

    @Bean
    @ConditionalOnMissingBean(RagDuplicateService.class)
    public RagDuplicateService noopRagDuplicateService() {
        return new NoopRagDuplicateService();
    }
}

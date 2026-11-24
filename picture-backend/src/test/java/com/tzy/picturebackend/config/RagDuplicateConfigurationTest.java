package com.tzy.picturebackend.config;

import com.tzy.picturebackend.event.NotificationEventPublisher;
import com.tzy.picturebackend.metrics.PictureMetrics;
import com.tzy.picturebackend.service.PictureService;
import com.tzy.picturebackend.service.RagDuplicateService;
import com.tzy.picturebackend.service.RagSearchService;
import com.tzy.picturebackend.service.impl.NoopRagDuplicateService;
import com.tzy.picturebackend.service.impl.RagDuplicateServiceImpl;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 近重复检测装配：只有「RAG 开启 + 搜索走 ES」才装配真实实现，其余组合都是空实现。
 */
class RagDuplicateConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig.class)
            .withBean(RagSearchService.class, () -> mock(RagSearchService.class))
            .withBean(PictureService.class, () -> mock(PictureService.class))
            .withBean(NotificationEventPublisher.class, () -> mock(NotificationEventPublisher.class))
            .withBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class))
            .withBean(PictureMetrics.class, () -> new PictureMetrics(new SimpleMeterRegistry()));

    @Test
    @DisplayName("默认关闭：注入 NoopRagDuplicateService")
    void disabledWiresNoop() {
        runner.run(context -> {
            RagDuplicateService service = context.getBean(RagDuplicateService.class);
            assertThat(service).isInstanceOf(NoopRagDuplicateService.class);
            assertThat(service.enabled()).isFalse();
        });
    }

    @Test
    @DisplayName("RAG 开启但搜索仍是 mysql：降级为空实现")
    void ragEnabledWithMysqlWiresNoop() {
        runner.withPropertyValues("app.rag.enabled=true", "app.search.engine=mysql")
                .run(context -> assertThat(context.getBean(RagDuplicateService.class))
                        .isInstanceOf(NoopRagDuplicateService.class));
    }

    @Test
    @DisplayName("RAG 开启且搜索走 ES：注入真实实现")
    void ragEnabledWithEsWiresReal() {
        runner.withPropertyValues("app.rag.enabled=true", "app.search.engine=es")
                .run(context -> assertThat(context.getBean(RagDuplicateService.class))
                        .isInstanceOf(RagDuplicateServiceImpl.class));
    }

    @Configuration
    @EnableConfigurationProperties(RagProperties.class)
    @Import(RagDuplicateConfiguration.class)
    static class TestConfig {
    }
}

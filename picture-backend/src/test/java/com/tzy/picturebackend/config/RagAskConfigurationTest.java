package com.tzy.picturebackend.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tzy.picturebackend.metrics.PictureMetrics;
import com.tzy.picturebackend.service.PictureService;
import com.tzy.picturebackend.service.RagAskService;
import com.tzy.picturebackend.service.RagIndexService;
import com.tzy.picturebackend.service.RagSearchService;
import com.tzy.picturebackend.service.impl.NoopRagAskService;
import com.tzy.picturebackend.service.impl.RagAskServiceImpl;
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
 * 问答装配：只有「RAG 开启 + 搜索走 ES」才装配真实实现，其余组合都是空实现。
 */
class RagAskConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig.class)
            .withBean(RagSearchService.class, () -> mock(RagSearchService.class))
            .withBean(RagIndexService.class, () -> mock(RagIndexService.class))
            .withBean(PictureService.class, () -> mock(PictureService.class))
            .withBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class))
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withBean(PictureMetrics.class, () -> new PictureMetrics(new SimpleMeterRegistry()));

    @Test
    @DisplayName("默认关闭：注入 NoopRagAskService")
    void disabledWiresNoop() {
        runner.run(context -> {
            RagAskService service = context.getBean(RagAskService.class);
            assertThat(service).isInstanceOf(NoopRagAskService.class);
            assertThat(service.enabled()).isFalse();
        });
    }

    @Test
    @DisplayName("RAG 开启但搜索仍是 mysql：降级为空实现")
    void ragEnabledWithMysqlWiresNoop() {
        runner.withPropertyValues("app.rag.enabled=true", "app.search.engine=mysql")
                .run(context -> assertThat(context.getBean(RagAskService.class))
                        .isInstanceOf(NoopRagAskService.class));
    }

    @Test
    @DisplayName("RAG 开启且搜索走 ES：注入 RagAskServiceImpl")
    void ragEnabledWithEsWiresRealService() {
        runner.withPropertyValues("app.rag.enabled=true", "app.search.engine=es")
                .run(context -> assertThat(context.getBean(RagAskService.class))
                        .isInstanceOf(RagAskServiceImpl.class));
    }

    @Configuration
    @EnableConfigurationProperties({RagProperties.class, AiProperties.class})
    @Import(RagAskConfiguration.class)
    static class TestConfig {
    }
}

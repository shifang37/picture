package com.tzy.picturebackend.config;

import lombok.Data;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * AI 建议配置：默认关闭，使用 DeepSeek 的 OpenAI 兼容接口。
 */
@Component
@ConfigurationProperties(prefix = "app.ai")
@Data
public class AiProperties {

    /**
     * 是否启用 AI 建议
     */
    private boolean enabled = false;

    /**
     * DeepSeek OpenAI 兼容接口地址
     */
    private String baseUrl = "https://api.deepseek.com";

    /**
     * DeepSeek API Key，生产环境通过环境变量注入
     */
    @ToString.Exclude
    private String apiKey;

    /**
     * 模型 ID；deepseek-flash 支持图片输入与 JSON Output
     */
    private String model = "deepseek-flash";

    /**
     * 每用户每日最多调用次数
     */
    private int dailyLimit = 20;

    /**
     * 失败后的最大重试次数（不含首次调用）
     */
    private int maxRetries = 2;

    /**
     * 重试基础间隔，单位毫秒
     */
    private long retryDelayMillis = 1000;

    /**
     * 单次响应最大 token 数，防止 JSON 被截断
     */
    private int maxTokens = 800;

    /**
     * 连接与单次请求超时
     */
    private Duration timeout = Duration.ofSeconds(60);
}

package com.tzy.picturebackend.manager.rag;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tzy.picturebackend.config.AiProperties;
import com.tzy.picturebackend.config.RagProperties;
import com.tzy.picturebackend.metrics.PictureMetrics;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * DeepSeek 图库问答实现。
 * <p>
 * 复用 {@code app.ai} 的 DeepSeek 地址、Key 与模型（图库问答与自动标注是同一个供应商），
 * 但超时取 {@code app.rag.ask.timeout}（默认 25s）：问答要拼候选、解析引用，比标注更敏感。
 * 请求使用 OpenAI 兼容 Chat Completions，{@code response_format=json_object} 约束输出，
 * 并显式关闭思考模式以降低延迟与费用。
 * <p>
 * 本类不做重试：失败直接由 {@code RagAskService} 降级为固定文案，避免 25s × 3 的尾延迟。
 */
@Slf4j
public class DeepSeekRagAnswerGenerator implements RagAnswerGenerator {

    private static final int ERROR_BODY_MAX_LENGTH = 500;

    /**
     * 回答 JSON 的最大输出 token；400 汉字约 600 token，留一点余量
     */
    private static final int MAX_OUTPUT_TOKENS = 800;

    private final AiProperties aiProperties;

    private final RagProperties ragProperties;

    private final ObjectMapper objectMapper;

    private final PictureMetrics pictureMetrics;

    private final HttpClient httpClient;

    public DeepSeekRagAnswerGenerator(AiProperties aiProperties, RagProperties ragProperties,
                                      ObjectMapper objectMapper, PictureMetrics pictureMetrics) {
        this.aiProperties = aiProperties;
        this.ragProperties = ragProperties;
        this.objectMapper = objectMapper;
        this.pictureMetrics = pictureMetrics;
        Duration timeout = askTimeout();
        this.httpClient = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    @Override
    public RagAnswerPayload generate(String systemPrompt, String userPrompt) {
        if (StrUtil.isBlank(aiProperties.getApiKey())) {
            throw RagAnswerException.permanent("未配置 DEEPSEEK_API_KEY，无法生成图库问答");
        }
        if (StrUtil.isBlank(systemPrompt) || StrUtil.isBlank(userPrompt)) {
            throw RagAnswerException.permanent("问答提示词为空");
        }
        String requestBody;
        try {
            requestBody = buildRequestBody(systemPrompt, userPrompt);
        } catch (JsonProcessingException e) {
            throw RagAnswerException.permanent("构造 DeepSeek 问答请求失败", e);
        }
        HttpRequest request = HttpRequest.newBuilder(buildEndpoint())
                .timeout(askTimeout())
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + aiProperties.getApiKey().trim())
                .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (HttpTimeoutException e) {
            throw RagAnswerException.retryable("DeepSeek 问答请求超时");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw RagAnswerException.permanent("DeepSeek 问答请求被中断", e);
        } catch (IOException e) {
            throw RagAnswerException.retryable("DeepSeek 问答网络异常: " + e.getMessage());
        }
        int statusCode = response.statusCode();
        if (statusCode < 200 || statusCode >= 300) {
            String message = "DeepSeek 问答接口返回 HTTP " + statusCode + ": " + snippet(response.body());
            if (statusCode == 429 || statusCode >= 500) {
                throw RagAnswerException.retryable(message);
            }
            throw RagAnswerException.permanent(message);
        }
        return parseResponse(response.body());
    }

    @Override
    public String model() {
        return StrUtil.blankToDefault(aiProperties.getModel(), "deepseek-flash");
    }

    /**
     * 构造 OpenAI 兼容请求体；独立包级可见，便于单测断言
     */
    String buildRequestBody(String systemPrompt, String userPrompt) throws JsonProcessingException {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", model());
        root.put("stream", false);
        root.put("max_tokens", MAX_OUTPUT_TOKENS);
        root.put("temperature", 0.2);
        root.set("response_format", objectMapper.createObjectNode().put("type", "json_object"));
        root.set("thinking", objectMapper.createObjectNode().put("type", "disabled"));

        ArrayNode messages = root.putArray("messages");
        messages.addObject().put("role", "system").put("content", systemPrompt);
        messages.addObject().put("role", "user").put("content", userPrompt);
        return objectMapper.writeValueAsString(root);
    }

    /**
     * 解析 choices[0].message.content；模型偶尔仍会包 Markdown 代码块，这里做一次容错剥离
     */
    RagAnswerPayload parseResponse(String responseBody) {
        JsonNode root;
        try {
            root = objectMapper.readTree(responseBody);
        } catch (JsonProcessingException e) {
            throw RagAnswerException.permanent("DeepSeek 问答响应不是合法 JSON", e);
        }
        // 按 token 计费：成功响应才累计，失败响应的 usage 不可信
        JsonNode usage = root.path("usage");
        pictureMetrics.ragTokens("prompt", usage.path("prompt_tokens").asLong(0));
        pictureMetrics.ragTokens("completion", usage.path("completion_tokens").asLong(0));

        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            throw RagAnswerException.permanent("DeepSeek 问答响应缺少 choices");
        }
        JsonNode choice = choices.get(0);
        String finishReason = choice.path("finish_reason").asText("");
        if ("content_filter".equals(finishReason)) {
            throw RagAnswerException.permanent("DeepSeek 拒绝回答该问题");
        }
        if ("length".equals(finishReason)) {
            throw RagAnswerException.permanent("DeepSeek 问答输出被截断");
        }
        String content = choice.path("message").path("content").asText("");
        content = stripCodeFence(content);
        if (StrUtil.isBlank(content)) {
            throw RagAnswerException.permanent("DeepSeek 问答返回空内容");
        }
        try {
            RagAnswerPayload payload = objectMapper.readerFor(RagAnswerPayload.class)
                    .without(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .readValue(content);
            if (payload == null) {
                throw RagAnswerException.permanent("DeepSeek 问答返回空结构");
            }
            return payload;
        } catch (JsonProcessingException e) {
            throw RagAnswerException.permanent("DeepSeek 问答输出不是合法 JSON", e);
        }
    }

    private URI buildEndpoint() {
        String baseUrl = StrUtil.removeSuffix(StrUtil.trim(aiProperties.getBaseUrl()), "/");
        if (StrUtil.isBlank(baseUrl)) {
            baseUrl = "https://api.deepseek.com";
        }
        return URI.create(baseUrl + "/chat/completions");
    }

    private Duration askTimeout() {
        Duration timeout = ragProperties.getAsk().getTimeout();
        return timeout == null ? Duration.ofSeconds(25) : timeout;
    }

    private String stripCodeFence(String content) {
        if (content == null) {
            return null;
        }
        String trimmed = content.trim();
        if (!trimmed.startsWith("```")) {
            return trimmed;
        }
        int firstLineEnd = trimmed.indexOf('\n');
        if (firstLineEnd > 0) {
            trimmed = trimmed.substring(firstLineEnd + 1).trim();
        }
        if (trimmed.endsWith("```")) {
            trimmed = trimmed.substring(0, trimmed.length() - 3).trim();
        }
        return trimmed;
    }

    private String snippet(String body) {
        if (body == null) {
            return "";
        }
        return body.length() <= ERROR_BODY_MAX_LENGTH ? body : body.substring(0, ERROR_BODY_MAX_LENGTH);
    }
}

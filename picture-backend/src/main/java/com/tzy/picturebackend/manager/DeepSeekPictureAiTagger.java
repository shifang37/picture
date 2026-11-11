package com.tzy.picturebackend.manager;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tzy.picturebackend.config.AiProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;

/**
 * DeepSeek 图片标注实现。
 * <p>
 * 使用 DeepSeek 的 OpenAI 兼容 Chat Completions 接口：`deepseek-flash` 支持 image_url 图片输入，
 * 通过 `response_format=json_object` 约束输出 JSON。请求显式关闭思考模式，降低延迟与费用。
 */
@Component
@Slf4j
public class DeepSeekPictureAiTagger implements PictureAiTagger {

    /**
     * 字典放进提示词的最大条数，避免提示词无限膨胀
     */
    private static final int MAX_DICT_ITEMS = 100;

    /**
     * 错误响应体日志截断长度
     */
    private static final int ERROR_BODY_MAX_LENGTH = 500;

    private static final String SYSTEM_PROMPT = """
            你是一名图片素材标注助手。请根据用户上传的图片，给出名称、简介、分类和标签建议。
            只输出 JSON，不要输出 Markdown 代码块或任何解释。JSON 格式示例：
            {"name":"海边日落","introduction":"夕阳下的海面与沙滩，画面以暖色调为主。","category":"风景","tags":["海边","日落","沙滩"]}
            要求：
            1. name 简洁准确，最多 20 个汉字，不超过 128 个字符；
            2. introduction 客观描述画面，最多 120 个汉字，不超过 512 个字符；
            3. category 必须从给定的分类字典中选择；无法判断时输出 null；
            4. tags 输出 3-8 个，优先从给定标签字典中选择，每个标签不超过 32 个字符；
            5. 不要输出字典外的分类，不要编造画面中不存在的内容。
            """;

    private final AiProperties aiProperties;

    private final ObjectMapper objectMapper;

    private final HttpClient httpClient;

    public DeepSeekPictureAiTagger(AiProperties aiProperties, ObjectMapper objectMapper) {
        this.aiProperties = aiProperties;
        this.objectMapper = objectMapper;
        Duration timeout = aiProperties.getTimeout() == null
                ? Duration.ofSeconds(60) : aiProperties.getTimeout();
        this.httpClient = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    @Override
    public AiSuggestionPayload suggest(String imageUrl, List<String> categoryDict, List<String> tagDict) {
        if (StrUtil.isBlank(aiProperties.getApiKey())) {
            throw AiSuggestionException.permanent("未配置 DEEPSEEK_API_KEY，无法调用 DeepSeek");
        }
        if (StrUtil.isBlank(imageUrl)) {
            throw AiSuggestionException.permanent("图片地址为空，无法调用 DeepSeek");
        }
        String requestBody;
        try {
            requestBody = buildRequestBody(imageUrl, categoryDict, tagDict);
        } catch (JsonProcessingException e) {
            throw AiSuggestionException.permanent("构造 DeepSeek 请求失败", e);
        }
        HttpRequest request = HttpRequest.newBuilder(buildEndpoint())
                .timeout(aiProperties.getTimeout() == null
                        ? Duration.ofSeconds(60) : aiProperties.getTimeout())
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + aiProperties.getApiKey().trim())
                .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw AiSuggestionException.permanent("DeepSeek 请求被中断", e);
        } catch (IOException e) {
            throw AiSuggestionException.retryable("DeepSeek 网络异常: " + e.getMessage());
        }
        int statusCode = response.statusCode();
        if (statusCode < 200 || statusCode >= 300) {
            long retryAfter = parseRetryAfter(response.headers().firstValue("Retry-After").orElse(null));
            String message = "DeepSeek 接口返回 HTTP " + statusCode + ": " + snippet(response.body());
            if (statusCode == 429 || statusCode >= 500) {
                throw AiSuggestionException.retryable(message, retryAfter);
            }
            throw AiSuggestionException.permanent(message);
        }
        return parseResponse(response.body());
    }

    /**
     * 构造 OpenAI 兼容的 chat/completions 请求体
     */
    String buildRequestBody(String imageUrl, List<String> categoryDict, List<String> tagDict)
            throws JsonProcessingException {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", aiProperties.getModel());
        root.put("stream", false);
        root.put("max_tokens", aiProperties.getMaxTokens());
        root.put("temperature", 0.2);
        root.set("response_format", objectMapper.createObjectNode().put("type", "json_object"));
        // 关闭思考模式：标注属于简单抽取任务，非思考模式延迟更低
        root.set("thinking", objectMapper.createObjectNode().put("type", "disabled"));

        ArrayNode messages = root.putArray("messages");
        ObjectNode system = messages.addObject();
        system.put("role", "system");
        system.put("content", SYSTEM_PROMPT);

        ObjectNode user = messages.addObject();
        user.put("role", "user");
        ArrayNode content = user.putArray("content");
        content.addObject()
                .put("type", "text")
                .put("text", buildUserPrompt(categoryDict, tagDict));
        ObjectNode image = content.addObject();
        image.put("type", "image_url");
        image.putObject("image_url").put("url", imageUrl);
        return objectMapper.writeValueAsString(root);
    }

    /**
     * 解析响应中的 choices[0].message.content
     */
    AiSuggestionPayload parseResponse(String responseBody) {
        JsonNode root;
        try {
            root = objectMapper.readTree(responseBody);
        } catch (JsonProcessingException e) {
            throw AiSuggestionException.permanent("DeepSeek 响应不是合法 JSON", e);
        }
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            throw AiSuggestionException.permanent("DeepSeek 响应缺少 choices");
        }
        JsonNode choice = choices.get(0);
        String finishReason = choice.path("finish_reason").asText("");
        if ("content_filter".equals(finishReason)) {
            throw AiSuggestionException.permanent("DeepSeek 拒绝生成该图片的建议");
        }
        if ("length".equals(finishReason)) {
            throw AiSuggestionException.permanent("DeepSeek 输出被截断");
        }
        String content = choice.path("message").path("content").asText("");
        content = stripCodeFence(content);
        if (StrUtil.isBlank(content)) {
            throw AiSuggestionException.permanent("DeepSeek 返回空内容");
        }
        try {
            AiSuggestionPayload payload = objectMapper.readerFor(AiSuggestionPayload.class)
                    .without(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .readValue(content);
            if (payload == null) {
                throw AiSuggestionException.permanent("DeepSeek 返回空建议");
            }
            return payload;
        } catch (JsonProcessingException e) {
            throw AiSuggestionException.permanent("DeepSeek 输出不是合法 JSON", e);
        }
    }

    private String buildUserPrompt(List<String> categoryDict, List<String> tagDict) {
        return "分类字典：" + formatDict(categoryDict) + "\n"
                + "标签字典：" + formatDict(tagDict) + "\n"
                + "请分析这张图片，并只输出 JSON。";
    }

    private String formatDict(List<String> dict) {
        if (dict == null || dict.isEmpty()) {
            return "（空，无法判断时输出 null）";
        }
        return dict.stream()
                .filter(StrUtil::isNotBlank)
                .limit(MAX_DICT_ITEMS)
                .collect(Collectors.joining("、"));
    }

    private URI buildEndpoint() {
        String baseUrl = StrUtil.removeSuffix(StrUtil.trim(aiProperties.getBaseUrl()), "/");
        if (StrUtil.isBlank(baseUrl)) {
            baseUrl = "https://api.deepseek.com";
        }
        return URI.create(baseUrl + "/chat/completions");
    }

    /**
     * 模型偶尔仍会返回 Markdown 代码块，这里做一次容错剥离
     */
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

    /**
     * Retry-After 按秒解析，非法值返回 0
     */
    private long parseRetryAfter(String value) {
        if (StrUtil.isBlank(value)) {
            return 0L;
        }
        try {
            long seconds = Long.parseLong(value.trim());
            return seconds <= 0 ? 0L : seconds * 1000L;
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private String snippet(String body) {
        if (body == null) {
            return "";
        }
        return body.length() <= ERROR_BODY_MAX_LENGTH ? body : body.substring(0, ERROR_BODY_MAX_LENGTH);
    }
}

package com.tzy.picturebackend.manager;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.tzy.picturebackend.config.AiProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 使用本地 HTTP Server 验证 DeepSeek 请求体、响应解析与错误分类，不依赖真实 API Key
 */
class DeepSeekPictureAiTaggerTest {

    private static final String IMAGE_URL = "https://bucket.cos.ap-beijing.myqcloud.com/public/1/a.jpg?imageMogr2/thumbnail/1024x";

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    private HttpServer server;

    private final AtomicReference<JsonNode> capturedRequest = new AtomicReference<>();

    private final AtomicReference<String> capturedAuthorization = new AtomicReference<>();

    private final AtomicInteger requestCount = new AtomicInteger();

    private final AtomicInteger responseStatus = new AtomicInteger(200);

    private final AtomicReference<String> responseBody = new AtomicReference<>("{}");

    private final AtomicReference<String> retryAfter = new AtomicReference<>();

    private DeepSeekPictureAiTagger tagger;

    private AiProperties properties;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            requestCount.incrementAndGet();
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            capturedRequest.set(objectMapper.readTree(request));
            capturedAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = responseBody.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            if (retryAfter.get() != null) {
                exchange.getResponseHeaders().add("Retry-After", retryAfter.get());
            }
            exchange.sendResponseHeaders(responseStatus.get(), body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        properties = new AiProperties();
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setApiKey("test-key");
        properties.setModel("deepseek-flash");
        properties.setTimeout(Duration.ofSeconds(5));
        properties.setMaxRetries(2);
        tagger = new DeepSeekPictureAiTagger(properties, objectMapper);
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("请求体：OpenAI 兼容 vision 格式、JSON Output、关闭思考模式")
    void suggest_buildsVisionJsonRequest() {
        respond(200, suggestionJson("海边日落", "风景", List.of("海边", "日落")), null);

        AiSuggestionPayload payload = tagger.suggest(IMAGE_URL, List.of("风景", "人物"),
                List.of("海边", "日落", "人像"));

        assertEquals("海边日落", payload.name());
        assertEquals("风景", payload.category());
        assertEquals(List.of("海边", "日落"), payload.tags());

        JsonNode request = capturedRequest.get();
        assertEquals("deepseek-flash", request.path("model").asText());
        assertFalse(request.path("stream").asBoolean());
        assertEquals("json_object", request.path("response_format").path("type").asText());
        assertEquals("disabled", request.path("thinking").path("type").asText());
        assertEquals("Bearer test-key", capturedAuthorization.get());
        JsonNode userContent = request.path("messages").get(1).path("content");
        assertTrue(userContent.get(0).path("text").asText().contains("分类字典"));
        assertTrue(userContent.get(0).path("text").asText().contains("风景"));
        assertEquals(IMAGE_URL, userContent.get(1).path("image_url").path("url").asText());
    }

    @Test
    @DisplayName("解析：模型偶尔返回 Markdown 代码块时也能解析")
    void suggest_stripsCodeFence() {
        respond(200, """
                {"choices":[{"finish_reason":"stop","message":{"content":"```json\\n{\\"name\\":\\"海边日落\\",\\"introduction\\":\\"夕阳下的海面\\",\\"category\\":\\"风景\\",\\"tags\\":[\\"海边\\"]}\\n```"}}]}
                """, null);

        AiSuggestionPayload payload = tagger.suggest(IMAGE_URL, List.of(), List.of());

        assertEquals("海边日落", payload.name());
        assertEquals(List.of("海边"), payload.tags());
    }

    @Test
    @DisplayName("错误：429 可重试，并带出 Retry-After")
    void suggest_retryableOn429() {
        respond(429, "{\"error\":\"rate limit\"}", "2");

        AiSuggestionException e = assertThrows(AiSuggestionException.class,
                () -> tagger.suggest(IMAGE_URL, List.of(), List.of()));

        assertTrue(e.isRetryable());
        assertEquals(2000L, e.getRetryAfterMillis());
    }

    @Test
    @DisplayName("错误：5xx 可重试")
    void suggest_retryableOn500() {
        respond(500, "{\"error\":\"server error\"}", null);

        AiSuggestionException e = assertThrows(AiSuggestionException.class,
                () -> tagger.suggest(IMAGE_URL, List.of(), List.of()));

        assertTrue(e.isRetryable());
    }

    @Test
    @DisplayName("错误：4xx 不可重试")
    void suggest_permanentOn400() {
        respond(400, "{\"error\":\"bad request\"}", null);

        AiSuggestionException e = assertThrows(AiSuggestionException.class,
                () -> tagger.suggest(IMAGE_URL, List.of(), List.of()));

        assertFalse(e.isRetryable());
    }

    @Test
    @DisplayName("错误：content_filter 按拒答处理，不重试")
    void suggest_permanentOnContentFilter() {
        respond(200, "{\"choices\":[{\"finish_reason\":\"content_filter\",\"message\":{\"content\":\"\"}}]}", null);

        AiSuggestionException e = assertThrows(AiSuggestionException.class,
                () -> tagger.suggest(IMAGE_URL, List.of(), List.of()));

        assertFalse(e.isRetryable());
    }

    @Test
    @DisplayName("错误：空内容或非法 JSON 不重试")
    void suggest_permanentOnUnparsableContent() {
        respond(200, "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"\"}}]}", null);
        assertFalse(assertThrows(AiSuggestionException.class,
                () -> tagger.suggest(IMAGE_URL, List.of(), List.of())).isRetryable());

        respond(200, "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"not json\"}}]}", null);
        assertFalse(assertThrows(AiSuggestionException.class,
                () -> tagger.suggest(IMAGE_URL, List.of(), List.of())).isRetryable());
    }

    @Test
    @DisplayName("配置：未设置 API Key 时不发请求")
    void suggest_failsFastWithoutApiKey() {
        properties.setApiKey("  ");

        AiSuggestionException e = assertThrows(AiSuggestionException.class,
                () -> tagger.suggest(IMAGE_URL, List.of(), List.of()));

        assertFalse(e.isRetryable());
        assertEquals(0, requestCount.get());
    }

    private void respond(int status, String body, String retryAfterValue) {
        responseStatus.set(status);
        responseBody.set(body);
        retryAfter.set(retryAfterValue);
    }

    private String suggestionJson(String name, String category, List<String> tags) {
        try {
            return objectMapper.writeValueAsString(objectMapper.createObjectNode()
                    .set("choices", objectMapper.createArrayNode().add(
                            objectMapper.createObjectNode()
                                    .put("finish_reason", "stop")
                                    .set("message", objectMapper.createObjectNode()
                                            .put("content", objectMapper.writeValueAsString(
                                                    new AiSuggestionPayload(name, "夕阳下的海面",
                                                            category, tags)))))));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}

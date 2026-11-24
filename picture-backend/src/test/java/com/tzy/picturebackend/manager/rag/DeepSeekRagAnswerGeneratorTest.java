package com.tzy.picturebackend.manager.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import com.tzy.picturebackend.config.AiProperties;
import com.tzy.picturebackend.config.RagProperties;
import com.tzy.picturebackend.metrics.PictureMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
 * 用本地 HTTP Server 验证 DeepSeek 问答请求体、JSON 解析、错误分类与 token 指标，
 * 不依赖真实 API Key。
 */
class DeepSeekRagAnswerGeneratorTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    private HttpServer server;

    private final AtomicReference<JsonNode> capturedRequest = new AtomicReference<>();

    private final AtomicReference<String> capturedAuthorization = new AtomicReference<>();

    private final AtomicInteger requestCount = new AtomicInteger();

    private final AtomicInteger responseStatus = new AtomicInteger(200);

    private final AtomicReference<String> responseBody = new AtomicReference<>("{}");

    private final AtomicInteger responseDelayMillis = new AtomicInteger(0);

    private DeepSeekRagAnswerGenerator generator;

    private AiProperties aiProperties;

    private RagProperties ragProperties;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            requestCount.incrementAndGet();
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            capturedRequest.set(objectMapper.readTree(request));
            capturedAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            if (responseDelayMillis.get() > 0) {
                try {
                    Thread.sleep(responseDelayMillis.get());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] body = responseBody.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(responseStatus.get(), body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        aiProperties = new AiProperties();
        aiProperties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        aiProperties.setApiKey("test-key");
        aiProperties.setModel("deepseek-flash");
        ragProperties = new RagProperties();
        ragProperties.getAsk().setTimeout(Duration.ofSeconds(5));
        generator = new DeepSeekRagAnswerGenerator(aiProperties, ragProperties, objectMapper,
                new PictureMetrics(meterRegistry));
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("请求体：JSON Output、关闭思考、system/user 消息与鉴权头")
    void generate_buildsJsonRequest() {
        respond(200, chatResponse("{\"answer\":\"有 2 张图比较合适\",\"citedPictureIds\":[12,35],\"refused\":false}"));

        RagAnswerPayload payload = generator.generate("系统规则", "问题与候选");

        assertEquals("有 2 张图比较合适", payload.answer());
        assertEquals(List.of(12L, 35L), payload.citedPictureIds());
        assertFalse(payload.refused());

        JsonNode request = capturedRequest.get();
        assertEquals("deepseek-flash", request.path("model").asText());
        assertFalse(request.path("stream").asBoolean());
        assertEquals("json_object", request.path("response_format").path("type").asText());
        assertEquals("disabled", request.path("thinking").path("type").asText());
        assertEquals("system", request.path("messages").get(0).path("role").asText());
        assertEquals("系统规则", request.path("messages").get(0).path("content").asText());
        assertEquals("user", request.path("messages").get(1).path("role").asText());
        assertEquals("问题与候选", request.path("messages").get(1).path("content").asText());
        assertEquals("Bearer test-key", capturedAuthorization.get());
        assertEquals(100.0, meterRegistry.get(PictureMetrics.RAG_TOKENS)
                .tag("direction", "prompt").counter().count());
        assertEquals(20.0, meterRegistry.get(PictureMetrics.RAG_TOKENS)
                .tag("direction", "completion").counter().count());
    }

    @Test
    @DisplayName("解析：模型返回 Markdown 代码块时也能解析")
    void generate_stripsCodeFence() {
        respond(200, chatResponse("```json\n{\"answer\":\"推荐这一张\",\"citedPictureIds\":[12],\"refused\":false}\n```"));

        RagAnswerPayload payload = generator.generate("系统规则", "问题");

        assertEquals("推荐这一张", payload.answer());
        assertEquals(List.of(12L), payload.citedPictureIds());
    }

    @Test
    @DisplayName("错误：缺少 API Key 时不发起请求")
    void generate_failsFastWithoutApiKey() {
        aiProperties.setApiKey(" ");

        RagAnswerException e = assertThrows(RagAnswerException.class,
                () -> generator.generate("系统规则", "问题"));

        assertFalse(e.isRetryable());
        assertEquals(0, requestCount.get());
    }

    @Test
    @DisplayName("错误：429/5xx 可重试，4xx 永久错误")
    void generate_classifiesHttpErrors() {
        respond(500, "{\"error\":\"server error\"}");
        assertTrue(assertThrows(RagAnswerException.class,
                () -> generator.generate("系统规则", "问题")).isRetryable());

        respond(400, "{\"error\":\"bad request\"}");
        assertFalse(assertThrows(RagAnswerException.class,
                () -> generator.generate("系统规则", "问题")).isRetryable());
    }

    @Test
    @DisplayName("错误：响应内容不是合法 JSON 时抛永久错误")
    void generate_failsOnMalformedContent() {
        respond(200, chatResponse("这不是 JSON"));

        RagAnswerException e = assertThrows(RagAnswerException.class,
                () -> generator.generate("系统规则", "问题"));

        assertFalse(e.isRetryable());
    }

    @Test
    @DisplayName("错误：content_filter / length 视为永久失败")
    void generate_failsOnFinishReason() {
        respond(200, chatResponseWithReason("content_filter", "{}"));
        assertThrows(RagAnswerException.class, () -> generator.generate("系统规则", "问题"));

        respond(200, chatResponseWithReason("length", "{}"));
        assertThrows(RagAnswerException.class, () -> generator.generate("系统规则", "问题"));
    }

    @Test
    @DisplayName("错误：超时单独标记，便于降级原因区分 ask_timeout")
    void generate_marksTimeout() {
        ragProperties.getAsk().setTimeout(Duration.ofMillis(100));
        generator = new DeepSeekRagAnswerGenerator(aiProperties, ragProperties, objectMapper,
                new PictureMetrics(meterRegistry));
        responseDelayMillis.set(400);
        respond(200, chatResponse("{\"answer\":\"x\",\"citedPictureIds\":[1],\"refused\":false}"));

        RagAnswerException e = assertThrows(RagAnswerException.class,
                () -> generator.generate("系统规则", "问题"));

        assertTrue(e.isTimeout(), "message=" + e.getMessage());
    }

    private void respond(int status, String body) {
        responseStatus.set(status);
        responseBody.set(body);
    }

    private String chatResponse(String content) {
        return chatResponseWithReason("stop", content);
    }

    private String chatResponseWithReason(String finishReason, String content) {
        ObjectNode root = objectMapper.createObjectNode();
        ObjectNode choice = root.putArray("choices").addObject();
        choice.put("finish_reason", finishReason);
        choice.putObject("message").put("content", content);
        root.putObject("usage").put("prompt_tokens", 100).put("completion_tokens", 20);
        return root.toString();
    }
}

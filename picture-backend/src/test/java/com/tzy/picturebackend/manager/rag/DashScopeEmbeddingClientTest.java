package com.tzy.picturebackend.manager.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 使用本地 JDK HttpServer 验证请求体、批次拆分、重试、校验与指标，不访问真实百炼接口。
 */
class DashScopeEmbeddingClientTest {

    private static final int DIMS = 4;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    private final List<JsonNode> requestBodies = Collections.synchronizedList(new ArrayList<>());

    private final AtomicInteger requestCount = new AtomicInteger();

    private final AtomicReference<String> lastPath = new AtomicReference<>();

    private final AtomicReference<double[]> responseVector = new AtomicReference<>(new double[]{1, 0, 0, 0});

    private final AtomicLong responseDelayMillis = new AtomicLong(0);

    private final Deque<StubResponse> scriptedResponses = new ConcurrentLinkedDeque<>();

    private HttpServer server;

    private ExecutorService executor;

    private RagProperties properties;

    private PictureMetrics pictureMetrics;

    private DashScopeEmbeddingClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.createContext("/", exchange -> {
            requestCount.incrementAndGet();
            lastPath.set(exchange.getRequestURI().getPath());
            String rawBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            JsonNode body = objectMapper.readTree(rawBody);
            requestBodies.add(body);
            long delay = responseDelayMillis.get();
            if (delay > 0) {
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            StubResponse scripted = scriptedResponses.pollFirst();
            int status = scripted == null ? 200 : scripted.status();
            String responseBody = scripted == null ? defaultResponse(body) : scripted.body();
            if (scripted != null && scripted.retryAfter() != null) {
                exchange.getResponseHeaders().add("Retry-After", scripted.retryAfter());
            }
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();

        properties = new RagProperties();
        properties.setEnabled(true);
        RagProperties.Embedding embedding = properties.getEmbedding();
        embedding.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        embedding.setApiKey("test-key");
        embedding.setTextDim(DIMS);
        embedding.setImageDim(DIMS);
        embedding.setBatchSize(10);
        embedding.setTimeout(Duration.ofSeconds(2));
        embedding.setMaxRetries(2);
        embedding.setRetryDelayMillis(10);
        pictureMetrics = new PictureMetrics(meterRegistry);
        client = newClient();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("文本：23 条按 batchSize=10 拆成 3 次请求，结果顺序完整")
    void embedTexts_splitsIntoBatches() {
        List<String> texts = new ArrayList<>();
        for (int i = 0; i < 23; i++) {
            texts.add("文本-" + i);
        }

        List<float[]> vectors = client.embedTexts(texts);

        assertEquals(23, vectors.size());
        assertEquals(3, requestBodies.size());
        assertEquals(10, requestBodies.get(0).path("input").size());
        assertEquals(10, requestBodies.get(1).path("input").size());
        assertEquals(3, requestBodies.get(2).path("input").size());
        assertEquals("text-embedding-v4", requestBodies.get(0).path("model").asText());
        assertEquals(DIMS, requestBodies.get(0).path("dimensions").asInt());
        assertEquals("/compatible-mode/v1/embeddings", lastPath.get());
    }

    @Test
    @DisplayName("文本：响应做 L2 归一化")
    void embedTexts_normalizesL2() {
        responseVector.set(new double[]{3, 4, 0, 0});

        List<float[]> vectors = client.embedTexts(List.of("海边日落"));

        float[] vector = vectors.get(0);
        assertEquals(0.6f, vector[0], 1e-6);
        assertEquals(0.8f, vector[1], 1e-6);
        assertEquals(0.0f, vector[2], 1e-6);
        assertEquals(0.0f, vector[3], 1e-6);
    }

    @Test
    @DisplayName("重试：429 后成功，且 Retry-After 生效")
    void embedTexts_retriesOn429() {
        scriptedResponses.add(new StubResponse(429, "{\"error\":\"rate limit\"}", "1"));
        long start = System.nanoTime();

        List<float[]> vectors = client.embedTexts(List.of("海边日落"));

        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
        assertEquals(1, vectors.size());
        assertEquals(2, requestCount.get(), "429 后应重试一次");
        assertTrue(elapsedMillis >= 900, "Retry-After: 1 应至少等待约 1 秒，实际 " + elapsedMillis + "ms");
    }

    @Test
    @DisplayName("重试：5xx 后成功")
    void embedTexts_retriesOn5xx() {
        scriptedResponses.add(new StubResponse(503, "{\"error\":\"busy\"}", null));

        List<float[]> vectors = client.embedTexts(List.of("海边日落"));

        assertEquals(1, vectors.size());
        assertEquals(2, requestCount.get());
    }

    @Test
    @DisplayName("重试：4xx 是永久错误，不重试")
    void embedTexts_permanentOn4xx() {
        scriptedResponses.add(new StubResponse(400, "{\"error\":\"bad request\"}", null));

        EmbeddingException exception = assertThrows(EmbeddingException.class,
                () -> client.embedTexts(List.of("海边日落")));

        assertFalse(exception.isRetryable());
        assertEquals(1, requestCount.get());
    }

    @Test
    @DisplayName("校验：维度不一致是永久错误")
    void embedTexts_permanentOnDimensionMismatch() {
        responseVector.set(new double[]{1, 0, 0});

        EmbeddingException exception = assertThrows(EmbeddingException.class,
                () -> client.embedTexts(List.of("海边日落")));

        assertFalse(exception.isRetryable());
        assertTrue(exception.getMessage().contains("维度"), exception.getMessage());
        assertEquals(1, requestCount.get());
    }

    @Test
    @DisplayName("校验：NaN/Inf 是永久错误")
    void embedTexts_permanentOnNonFiniteValue() {
        // 1e39 超出 float 上限，JSON 合法但转成 float 会变成 Infinity
        responseVector.set(new double[]{1e39, 0, 0, 0});

        EmbeddingException exception = assertThrows(EmbeddingException.class,
                () -> client.embedTexts(List.of("海边日落")));

        assertFalse(exception.isRetryable());
        assertTrue(exception.getMessage().contains("NaN/Inf"), exception.getMessage());
    }

    @Test
    @DisplayName("校验：零向量无法归一化")
    void embedTexts_permanentOnZeroVector() {
        responseVector.set(new double[]{0, 0, 0, 0});

        EmbeddingException exception = assertThrows(EmbeddingException.class,
                () -> client.embedTexts(List.of("海边日落")));

        assertFalse(exception.isRetryable());
        assertTrue(exception.getMessage().contains("模长"), exception.getMessage());
    }

    @Test
    @DisplayName("超时：不重试，直接永久失败")
    void embedTexts_timeoutIsNotRetried() {
        properties.getEmbedding().setTimeout(Duration.ofMillis(100));
        client = newClient();
        responseDelayMillis.set(500);

        EmbeddingException exception = assertThrows(EmbeddingException.class,
                () -> client.embedTexts(List.of("海边日落")));

        assertFalse(exception.isRetryable());
        assertTrue(exception.getMessage().contains("超时"), exception.getMessage());
        assertEquals(1, requestCount.get(), "超时不应重试");
    }

    @Test
    @DisplayName("查询向量：5xx 不重试，快速失败交给 BM25 兜底")
    void embedQuery_doesNotRetryOn5xx() {
        scriptedResponses.add(new StubResponse(503, "{\"error\":\"busy\"}", null));

        EmbeddingException exception = assertThrows(EmbeddingException.class,
                () -> client.embedQuery("海边日落"));

        assertTrue(exception.isRetryable());
        assertEquals(1, requestCount.get(), "查询向量不允许重试");
    }

    @Test
    @DisplayName("查询向量：使用 query-timeout，超时快速失败")
    void embedQuery_usesQueryTimeout() {
        properties.getEmbedding().setQueryTimeout(Duration.ofMillis(100));
        client = newClient();
        responseDelayMillis.set(500);
        long start = System.nanoTime();

        EmbeddingException exception = assertThrows(EmbeddingException.class,
                () -> client.embedQuery("海边日落"));

        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
        assertFalse(exception.isRetryable());
        assertTrue(exception.getMessage().contains("超时"), exception.getMessage());
        assertTrue(elapsedMillis < 450, "查询向量应在 ~100ms 内超时，实际 " + elapsedMillis + "ms");
    }

    @Test
    @DisplayName("图片：走多模态接口，一次只传一张（接口限制 batch size=[1,1]）")
    void embedImages_usesMultimodalEndpointOneByOne() {
        List<float[]> vectors = client.embedImages(List.of("https://example.com/a.jpg",
                "data:image/jpeg;base64,AAAA"));

        assertEquals(2, vectors.size());
        assertEquals(2, requestBodies.size(), "两张图片必须拆成两次请求");
        assertEquals("/api/v1/services/embeddings/multimodal-embedding/multimodal-embedding",
                lastPath.get());
        for (int i = 0; i < 2; i++) {
            JsonNode body = requestBodies.get(i);
            assertEquals("multimodal-embedding-v1", body.path("model").asText());
            assertEquals(1, body.path("input").path("contents").size());
        }
        assertEquals("https://example.com/a.jpg",
                requestBodies.get(0).path("input").path("contents").get(0).path("image").asText());
        assertEquals("data:image/jpeg;base64,AAAA",
                requestBodies.get(1).path("input").path("contents").get(0).path("image").asText());
    }

    @Test
    @DisplayName("配置：未设置 API Key 时不发请求")
    void embedTexts_failsFastWithoutApiKey() {
        properties.getEmbedding().setApiKey("  ");

        EmbeddingException exception = assertThrows(EmbeddingException.class,
                () -> client.embedTexts(List.of("海边日落")));

        assertFalse(exception.isRetryable());
        assertEquals(0, requestCount.get());
    }

    @Test
    @DisplayName("指标：成功与失败分别计数，并记录耗时")
    void embedTexts_recordsMetrics() {
        client.embedTexts(List.of("海边日落"));

        assertEquals(1.0, meterRegistry.get(PictureMetrics.RAG_EMBED)
                .tag("kind", "text").tag("result", "ok").counter().count());
        assertEquals(1L, meterRegistry.get(PictureMetrics.RAG_EMBED)
                .tag("kind", "text").timer().count());

        scriptedResponses.add(new StubResponse(400, "{\"error\":\"bad request\"}", null));
        assertThrows(EmbeddingException.class, () -> client.embedTexts(List.of("失败")));

        assertEquals(1.0, meterRegistry.get(PictureMetrics.RAG_EMBED)
                .tag("kind", "text").tag("result", "fail").counter().count());
    }

    @Test
    @DisplayName("空输入：不发起请求，返回空列表")
    void embed_emptyInputsDoNotCallApi() {
        assertTrue(client.embedTexts(List.of()).isEmpty());
        assertTrue(client.embedImages(List.of()).isEmpty());
        assertEquals(0, requestCount.get());
    }

    private DashScopeEmbeddingClient newClient() {
        return new DashScopeEmbeddingClient(properties, objectMapper, pictureMetrics);
    }

    private String defaultResponse(JsonNode request) {
        if (lastPath.get() != null && lastPath.get().contains("multimodal")) {
            int count = request.path("input").path("contents").size();
            return multimodalResponse(count, responseVector.get());
        }
        int count = request.path("input").size();
        return textResponse(count, responseVector.get());
    }

    private String textResponse(int count, double[] vector) {
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode data = root.putArray("data");
        for (int i = 0; i < count; i++) {
            ObjectNode item = data.addObject();
            item.put("index", i);
            item.put("object", "embedding");
            ArrayNode embedding = item.putArray("embedding");
            for (double value : vector) {
                embedding.add(value);
            }
        }
        root.set("usage", objectMapper.createObjectNode()
                .put("prompt_tokens", 1).put("total_tokens", 1));
        return root.toString();
    }

    private String multimodalResponse(int count, double[] vector) {
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode embeddings = root.putObject("output").putArray("embeddings");
        for (int i = 0; i < count; i++) {
            ObjectNode item = embeddings.addObject();
            item.put("index", i);
            ArrayNode embedding = item.putArray("embedding");
            for (double value : vector) {
                embedding.add(value);
            }
        }
        root.set("usage", objectMapper.createObjectNode()
                .put("image_tokens", 128).put("input_tokens", 0));
        return root.toString();
    }

    private record StubResponse(int status, String body, String retryAfter) {
    }
}

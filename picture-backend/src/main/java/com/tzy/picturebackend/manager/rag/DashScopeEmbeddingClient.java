package com.tzy.picturebackend.manager.rag;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tzy.picturebackend.config.RagProperties;
import com.tzy.picturebackend.metrics.PictureMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 百炼向量化实现：文本走 OpenAI 兼容接口，图片走多模态接口。
 * <p>
 * 只在 {@code app.rag.enabled=true} 时装配。关键约束：
 * <ul>
 *   <li>批大小、超时、重试次数全部可配，默认批 10；</li>
 *   <li>429 / 5xx / 网络异常可重试，429 优先使用 Retry-After；超时不重试；</li>
 *   <li>响应必须条数一致、维度一致、无 NaN/Inf，并做 L2 归一化；</li>
 *   <li>Key 只从配置读取，不写日志；错误日志只截断响应体。</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(prefix = "app.rag", name = "enabled", havingValue = "true")
@Slf4j
public class DashScopeEmbeddingClient implements EmbeddingClient {

    /**
     * 文本向量接口（OpenAI 兼容）
     */
    private static final String TEXT_PATH = "/compatible-mode/v1/embeddings";

    /**
     * 多模态向量接口
     */
    private static final String MULTIMODAL_PATH =
            "/api/v1/services/embeddings/multimodal-embedding/multimodal-embedding";

    /**
     * Retry-After 的等待上限，避免服务端给出离谱值把线程挂住
     */
    private static final long MAX_RETRY_AFTER_MILLIS = 5000L;

    /**
     * 错误响应体日志截断长度
     */
    private static final int ERROR_BODY_MAX_LENGTH = 500;

    private final RagProperties.Embedding embedding;

    private final ObjectMapper objectMapper;

    private final PictureMetrics pictureMetrics;

    private final HttpClient httpClient;

    public DashScopeEmbeddingClient(RagProperties ragProperties, ObjectMapper objectMapper,
                                    PictureMetrics pictureMetrics) {
        this.embedding = ragProperties.getEmbedding();
        this.objectMapper = objectMapper;
        this.pictureMetrics = pictureMetrics;
        if (embedding.getBatchSize() <= 0) {
            throw new IllegalStateException("app.rag.embedding.batch-size 必须大于 0");
        }
        if (embedding.getTextDim() <= 0 || embedding.getImageDim() <= 0) {
            throw new IllegalStateException("app.rag.embedding 的向量维度必须大于 0");
        }
        if (embedding.getTextDim() != embedding.getImageDim()) {
            throw new IllegalStateException("app.rag.embedding 的文本与图片向量维度必须一致: textDim="
                    + embedding.getTextDim() + ", imageDim=" + embedding.getImageDim());
        }
        Duration timeout = embedding.getTimeout() == null
                ? Duration.ofSeconds(30) : embedding.getTimeout();
        this.httpClient = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    @Override
    public List<float[]> embedTexts(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        List<float[]> result = new ArrayList<>(texts.size());
        for (List<String> batch : batches(texts)) {
            result.addAll(callBatch("text", TEXT_PATH, buildTextBody(batch), batch.size()));
        }
        return result;
    }

    @Override
    public List<float[]> embedImages(List<String> imageInputs) {
        if (imageInputs == null || imageInputs.isEmpty()) {
            return List.of();
        }
        List<float[]> result = new ArrayList<>(imageInputs.size());
        // 多模态接口限制 image batch size 必须是 [1, 1]（实测 2 张会返回
        // InvalidParameter: image batch size can should be [1, 1]），因此逐张调用；
        // 全量重建时的并发由 RagIndexService 控制，不在这里偷用 batch。
        for (String imageInput : imageInputs) {
            result.addAll(callBatch("image", MULTIMODAL_PATH,
                    buildImageBody(List.of(imageInput)), 1));
        }
        return result;
    }

    @Override
    public String model() {
        return embedding.getTextModel();
    }

    @Override
    public String version() {
        return "v1";
    }

    @Override
    public int dims() {
        return embedding.getTextDim();
    }

    /**
     * 按 batchSize 切片；返回的是只读子视图，避免复制大文本
     */
    private List<List<String>> batches(List<String> values) {
        List<List<String>> result = new ArrayList<>();
        int size = embedding.getBatchSize();
        for (int i = 0; i < values.size(); i += size) {
            result.add(values.subList(i, Math.min(values.size(), i + size)));
        }
        return result;
    }

    private ObjectNode buildTextBody(List<String> batch) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", embedding.getTextModel());
        ArrayNode input = root.putArray("input");
        batch.forEach(input::add);
        root.put("dimensions", embedding.getTextDim());
        return root;
    }

    private ObjectNode buildImageBody(List<String> batch) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", embedding.getImageModel());
        ArrayNode contents = root.putObject("input").putArray("contents");
        for (String image : batch) {
            contents.addObject().put("image", image);
        }
        return root;
    }

    /**
     * 调用一个批次，处理重试与指标。
     * <p>
     * 每次尝试都记一次指标：重试前的失败也是一次真实的调用失败，便于告警。
     */
    private List<float[]> callBatch(String kind, String path, ObjectNode body, int expectedCount) {
        int attempt = 0;
        int maxRetries = Math.max(0, embedding.getMaxRetries());
        while (true) {
            long startNanos = System.nanoTime();
            try {
                JsonNode response = doPost(path, body);
                List<float[]> vectors = parseVectors(response, kind, expectedCount);
                pictureMetrics.ragEmbed(kind, true, System.nanoTime() - startNanos);
                return vectors;
            } catch (EmbeddingException e) {
                pictureMetrics.ragEmbed(kind, false, System.nanoTime() - startNanos);
                if (!e.isRetryable() || attempt >= maxRetries) {
                    throw e;
                }
                attempt++;
                long delay = retryDelayMillis(e, attempt);
                log.warn("Embedding 调用失败，{}ms 后第 {} 次重试, kind={}, message={}",
                        delay, attempt, kind, e.getMessage());
                sleep(delay);
            }
        }
    }

    private long retryDelayMillis(EmbeddingException e, int attempt) {
        if (e.getRetryAfterMillis() > 0) {
            return Math.min(e.getRetryAfterMillis(), MAX_RETRY_AFTER_MILLIS);
        }
        return Math.max(0L, embedding.getRetryDelayMillis() * attempt);
    }

    private JsonNode doPost(String path, ObjectNode body) {
        if (StrUtil.isBlank(embedding.getApiKey())) {
            throw EmbeddingException.permanent("未配置 DASHSCOPE_API_KEY，无法调用百炼向量化");
        }
        String payload;
        try {
            payload = objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw EmbeddingException.permanent("构造向量化请求失败", e);
        }
        Duration timeout = embedding.getTimeout() == null
                ? Duration.ofSeconds(30) : embedding.getTimeout();
        HttpRequest request = HttpRequest.newBuilder(buildEndpoint(path))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + embedding.getApiKey().trim())
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (HttpTimeoutException e) {
            // 超时通常意味着服务端已经拥塞，重试只会放大压力
            throw EmbeddingException.permanent("向量化请求超时", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw EmbeddingException.permanent("向量化请求被中断", e);
        } catch (IOException e) {
            throw EmbeddingException.retryable("向量化网络异常: " + e.getMessage());
        }
        int statusCode = response.statusCode();
        if (statusCode < 200 || statusCode >= 300) {
            long retryAfter = parseRetryAfter(response.headers().firstValue("Retry-After").orElse(null));
            String message = "Embedding 接口返回 HTTP " + statusCode + ": " + snippet(response.body());
            if (statusCode == 429 || statusCode >= 500) {
                throw EmbeddingException.retryable(message, retryAfter);
            }
            throw EmbeddingException.permanent(message);
        }
        try {
            return objectMapper.readTree(response.body());
        } catch (JsonProcessingException e) {
            throw EmbeddingException.permanent("Embedding 响应不是合法 JSON", e);
        }
    }

    /**
     * 文本响应在 data[].embedding，多模态响应在 output.embeddings[].embedding
     */
    private List<float[]> parseVectors(JsonNode response, String kind, int expectedCount) {
        JsonNode items = "image".equals(kind)
                ? response.path("output").path("embeddings")
                : response.path("data");
        if (!items.isArray()) {
            throw EmbeddingException.permanent("Embedding 响应缺少向量数组, kind=" + kind);
        }
        if (items.size() != expectedCount) {
            throw EmbeddingException.permanent("Embedding 返回条数与输入不一致: expected="
                    + expectedCount + ", actual=" + items.size());
        }
        List<float[]> vectors = new ArrayList<>(items.size());
        for (JsonNode item : items) {
            vectors.add(normalize(item.path("embedding")));
        }
        return vectors;
    }

    /**
     * 维度校验 + 有限值校验 + L2 归一化
     */
    private float[] normalize(JsonNode vectorNode) {
        int dims = embedding.getTextDim();
        if (!vectorNode.isArray() || vectorNode.size() != dims) {
            throw EmbeddingException.permanent("Embedding 维度不合法: expected=" + dims
                    + ", actual=" + (vectorNode.isArray() ? vectorNode.size() : "non-array"));
        }
        float[] vector = new float[dims];
        double sumSquares = 0.0;
        for (int i = 0; i < dims; i++) {
            double value = vectorNode.get(i).asDouble();
            float casted = (float) value;
            if (!Double.isFinite(value) || !Float.isFinite(casted)) {
                throw EmbeddingException.permanent("Embedding 含 NaN/Inf，拒绝写入索引");
            }
            vector[i] = casted;
            sumSquares += value * value;
        }
        double norm = Math.sqrt(sumSquares);
        if (!(norm > 0.0) || !Double.isFinite(norm)) {
            throw EmbeddingException.permanent("Embedding 向量模长为 0，无法归一化");
        }
        for (int i = 0; i < dims; i++) {
            vector[i] = (float) (vector[i] / norm);
        }
        return vector;
    }

    private URI buildEndpoint(String path) {
        String baseUrl = StrUtil.removeSuffix(StrUtil.trim(embedding.getBaseUrl()), "/");
        if (StrUtil.isBlank(baseUrl)) {
            baseUrl = "https://dashscope.aliyuncs.com";
        }
        return URI.create(baseUrl + path);
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

    private void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw EmbeddingException.permanent("向量化重试被中断", e);
        }
    }
}

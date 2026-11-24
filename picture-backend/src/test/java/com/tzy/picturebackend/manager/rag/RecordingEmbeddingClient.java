package com.tzy.picturebackend.manager.rag;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * 测试替身：确定性向量，不发起任何网络请求。
 * <p>
 * 规划里写的是 blake2b，但 JDK 与项目依赖都没有现成实现；用 SHA-256 散列填充同样满足
 * 「同文本同向量、不同文本不同向量、可复现」的要求，且不引入新依赖。
 */
public class RecordingEmbeddingClient implements EmbeddingClient {

    private static final int DEFAULT_DIMS = 1024;

    private final int dims;

    private final List<List<String>> textBatches = new ArrayList<>();

    private final List<List<String>> imageBatches = new ArrayList<>();

    public RecordingEmbeddingClient() {
        this(DEFAULT_DIMS);
    }

    public RecordingEmbeddingClient(int dims) {
        if (dims <= 0) {
            throw new IllegalArgumentException("dims 必须大于 0");
        }
        this.dims = dims;
    }

    @Override
    public List<float[]> embedTexts(List<String> texts) {
        textBatches.add(List.copyOf(texts));
        return vectors(texts);
    }

    @Override
    public List<float[]> embedImages(List<String> imageInputs) {
        imageBatches.add(List.copyOf(imageInputs));
        return vectors(imageInputs);
    }

    @Override
    public String model() {
        return "recording-text-embedding";
    }

    @Override
    public String version() {
        return "test-v1";
    }

    @Override
    public int dims() {
        return dims;
    }

    public List<List<String>> textBatches() {
        return textBatches;
    }

    public List<List<String>> imageBatches() {
        return imageBatches;
    }

    private List<float[]> vectors(List<String> values) {
        List<float[]> result = new ArrayList<>(values.size());
        for (String value : values) {
            result.add(vector(value));
        }
        return result;
    }

    private float[] vector(String value) {
        byte[] seed = digest(value);
        float[] vector = new float[dims];
        double sumSquares = 0.0;
        for (int i = 0; i < dims; i++) {
            float component = ((seed[i % seed.length] & 0xFF) - 127.5f) / 127.5f;
            vector[i] = component;
            sumSquares += component * component;
        }
        double norm = Math.sqrt(sumSquares);
        // SHA-256 全零的概率可以忽略，这里仍兜底，避免产生 NaN
        if (norm == 0.0) {
            vector[0] = 1.0f;
            return vector;
        }
        for (int i = 0; i < dims; i++) {
            vector[i] = (float) (vector[i] / norm);
        }
        return vector;
    }

    private byte[] digest(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return digest.digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK 缺少 SHA-256", e);
        }
    }
}

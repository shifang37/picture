package com.tzy.picturebackend.manager.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 确定性测试替身：同文本同向量、不联网、记录批次。
 */
class RecordingEmbeddingClientTest {

    @Test
    @DisplayName("同文本产生同向量，不同文本产生不同向量")
    void vectorsAreDeterministic() {
        RecordingEmbeddingClient client = new RecordingEmbeddingClient(8);

        float[] first = client.embedTexts(List.of("海边日落")).get(0);
        float[] again = client.embedTexts(List.of("海边日落")).get(0);
        float[] other = client.embedTexts(List.of("城市夜景")).get(0);

        assertArrayEquals(first, again);
        assertNotEquals(first[0], other[0]);
        assertEquals(8, first.length);
    }

    @Test
    @DisplayName("记录每次调用的批次，便于断言批量行为")
    void recordsBatches() {
        RecordingEmbeddingClient client = new RecordingEmbeddingClient(4);

        client.embedTexts(List.of("a", "b"));
        client.embedImages(List.of("img-1"));

        assertEquals(1, client.textBatches().size());
        assertEquals(List.of("a", "b"), client.textBatches().get(0));
        assertEquals(1, client.imageBatches().size());
        assertEquals(List.of("img-1"), client.imageBatches().get(0));
    }

    @Test
    @DisplayName("向量已做 L2 归一化")
    void vectorsAreNormalized() {
        RecordingEmbeddingClient client = new RecordingEmbeddingClient(16);

        float[] vector = client.embedTexts(List.of("海边日落")).get(0);

        double sumSquares = 0.0;
        for (float value : vector) {
            sumSquares += value * value;
        }
        assertEquals(1.0, Math.sqrt(sumSquares), 1e-5);
        assertTrue(client.dims() == 16);
    }
}

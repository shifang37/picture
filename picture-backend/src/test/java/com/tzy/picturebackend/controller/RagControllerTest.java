package com.tzy.picturebackend.controller;

import com.tzy.picturebackend.config.RagProperties;
import com.tzy.picturebackend.exception.GlobalExceptionHandler;
import com.tzy.picturebackend.manager.rag.RecordingEmbeddingClient;
import com.tzy.picturebackend.metrics.PictureMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * RAG 状态接口：关闭时统一返回 OPERATION_ERROR，开启时返回模型与维度。
 */
class RagControllerTest {

    private MockMvc mockMvc;

    private RagProperties ragProperties;

    @BeforeEach
    void setUp() {
        ragProperties = new RagProperties();
        RagController controller = new RagController();
        ReflectionTestUtils.setField(controller, "ragProperties", ragProperties);
        ReflectionTestUtils.setField(controller, "embeddingClient", new RecordingEmbeddingClient());
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler(
                        new PictureMetrics(new SimpleMeterRegistry())))
                .build();
    }

    @Test
    @DisplayName("关闭时：返回 50001 未启用 RAG，不暴露模型信息")
    void status_disabledReturnsOperationError() throws Exception {
        mockMvc.perform(get("/rag/status").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(50001))
                .andExpect(jsonPath("$.message").value("未启用 RAG"));
    }

    @Test
    @DisplayName("开启时：返回开关、模型、维度与索引名")
    void status_enabledReturnsModelInfo() throws Exception {
        ragProperties.setEnabled(true);

        mockMvc.perform(get("/rag/status").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.enabled").value(true))
                .andExpect(jsonPath("$.data.textModel").value("recording-text-embedding"))
                .andExpect(jsonPath("$.data.imageModel").value("multimodal-embedding-v1"))
                .andExpect(jsonPath("$.data.dims").value(1024))
                .andExpect(jsonPath("$.data.indexName").value("picture_vec_v1"));
    }
}

package com.tzy.picturebackend.controller;

import com.tzy.picturebackend.config.RagProperties;
import com.tzy.picturebackend.exception.GlobalExceptionHandler;
import com.tzy.picturebackend.manager.rag.EmbeddingText;
import com.tzy.picturebackend.manager.rag.RecordingEmbeddingClient;
import com.tzy.picturebackend.metrics.PictureMetrics;
import com.tzy.picturebackend.model.vo.rag.RagReindexVO;
import com.tzy.picturebackend.service.RagEmbeddingStateService;
import com.tzy.picturebackend.service.RagIndexService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * RAG 管理接口：关闭时统一返回 OPERATION_ERROR；开启时返回模型、索引与数量统计。
 */
class RagControllerTest {

    private MockMvc mockMvc;

    private RagProperties ragProperties;

    private RagIndexService ragIndexService;

    private RagEmbeddingStateService ragEmbeddingStateService;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        ragProperties = new RagProperties();
        ragIndexService = mock(RagIndexService.class);
        ragEmbeddingStateService = mock(RagEmbeddingStateService.class);
        ObjectProvider<RagIndexService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        RagController controller = new RagController();
        ReflectionTestUtils.setField(controller, "ragProperties", ragProperties);
        ReflectionTestUtils.setField(controller, "embeddingClient", new RecordingEmbeddingClient());
        ReflectionTestUtils.setField(controller, "embeddingText", new EmbeddingText());
        ReflectionTestUtils.setField(controller, "ragEmbeddingStateService", ragEmbeddingStateService);
        ReflectionTestUtils.setField(controller, "ragIndexServiceProvider", provider);
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
    @DisplayName("开启但搜索后端不是 ES：返回 50001，不查询索引")
    void status_enabledWithoutElasticsearchReturnsOperationError() throws Exception {
        ragProperties.setEnabled(true);

        mockMvc.perform(get("/rag/status").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(50001))
                .andExpect(jsonPath("$.message").value("未启用 Elasticsearch 搜索，RAG 向量索引不可用"));
    }

    @Test
    @DisplayName("开启时：返回开关、模型、维度、索引与数量统计")
    @SuppressWarnings("unchecked")
    void status_enabledReturnsIndexInfo() throws Exception {
        ragProperties.setEnabled(true);
        ObjectProvider<RagIndexService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(ragIndexService);
        when(ragIndexService.indexName()).thenReturn("picture_vec_v1");
        when(ragIndexService.countIndexed()).thenReturn(108L);
        when(ragIndexService.stale()).thenReturn(false);
        when(ragEmbeddingStateService.countPendingPublic()).thenReturn(2L);
        when(ragEmbeddingStateService.countFailed()).thenReturn(1L);
        RagController controller = new RagController();
        ReflectionTestUtils.setField(controller, "ragProperties", ragProperties);
        ReflectionTestUtils.setField(controller, "embeddingClient", new RecordingEmbeddingClient());
        ReflectionTestUtils.setField(controller, "embeddingText", new EmbeddingText());
        ReflectionTestUtils.setField(controller, "ragEmbeddingStateService", ragEmbeddingStateService);
        ReflectionTestUtils.setField(controller, "ragIndexServiceProvider", provider);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler(
                        new PictureMetrics(new SimpleMeterRegistry())))
                .build();

        mockMvc.perform(get("/rag/status").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.enabled").value(true))
                .andExpect(jsonPath("$.data.textModel").value("recording-text-embedding"))
                .andExpect(jsonPath("$.data.imageModel").value("multimodal-embedding-v1"))
                .andExpect(jsonPath("$.data.dims").value(1024))
                .andExpect(jsonPath("$.data.indexName").value("picture_vec_v1"))
                .andExpect(jsonPath("$.data.alias").value("picture_vec"))
                .andExpect(jsonPath("$.data.docCount").value(108))
                .andExpect(jsonPath("$.data.pendingCount").value(2))
                .andExpect(jsonPath("$.data.failedCount").value(1))
                .andExpect(jsonPath("$.data.stale").value(false));
    }

    @Test
    @DisplayName("重建：返回 indexed / skipped / failed / switched")
    @SuppressWarnings("unchecked")
    void reindex_returnsCounts() throws Exception {
        ragProperties.setEnabled(true);
        ObjectProvider<RagIndexService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(ragIndexService);
        RagReindexVO reindex = new RagReindexVO();
        reindex.setIndexed(108);
        reindex.setSkipped(0);
        reindex.setFailed(0);
        reindex.setSwitched(true);
        reindex.setMessage("重建完成");
        when(ragIndexService.reindexAll()).thenReturn(reindex);
        RagController controller = new RagController();
        ReflectionTestUtils.setField(controller, "ragProperties", ragProperties);
        ReflectionTestUtils.setField(controller, "embeddingClient", new RecordingEmbeddingClient());
        ReflectionTestUtils.setField(controller, "embeddingText", new EmbeddingText());
        ReflectionTestUtils.setField(controller, "ragEmbeddingStateService", ragEmbeddingStateService);
        ReflectionTestUtils.setField(controller, "ragIndexServiceProvider", provider);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler(
                        new PictureMetrics(new SimpleMeterRegistry())))
                .build();

        mockMvc.perform(post("/rag/reindex").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.indexed").value(108))
                .andExpect(jsonPath("$.data.failed").value(0))
                .andExpect(jsonPath("$.data.switched").value(true));
    }
}

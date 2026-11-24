package com.tzy.picturebackend.controller;

import com.tzy.picturebackend.config.RagProperties;
import com.tzy.picturebackend.config.SearchProperties;
import com.tzy.picturebackend.exception.BusinessException;
import com.tzy.picturebackend.exception.ErrorCode;
import com.tzy.picturebackend.exception.GlobalExceptionHandler;
import com.tzy.picturebackend.manager.RagSearchCache;
import com.tzy.picturebackend.manager.rag.EmbeddingText;
import com.tzy.picturebackend.manager.rag.RecordingEmbeddingClient;
import com.tzy.picturebackend.metrics.PictureMetrics;
import com.tzy.picturebackend.model.dto.rag.RagAskRequest;
import com.tzy.picturebackend.model.entity.User;
import com.tzy.picturebackend.model.vo.rag.RagAskResponse;
import com.tzy.picturebackend.model.vo.rag.RagCitationVO;
import com.tzy.picturebackend.model.vo.rag.RagReindexVO;
import com.tzy.picturebackend.service.RagAskService;
import com.tzy.picturebackend.service.RagEmbeddingStateService;
import com.tzy.picturebackend.service.RagIndexService;
import com.tzy.picturebackend.service.UserService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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

    private SearchProperties searchProperties;

    private RagIndexService ragIndexService;

    private RagEmbeddingStateService ragEmbeddingStateService;

    private RagSearchCache ragSearchCache;

    private RagAskService ragAskService;

    private UserService userService;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        ragProperties = new RagProperties();
        searchProperties = new SearchProperties();
        ragIndexService = mock(RagIndexService.class);
        ragEmbeddingStateService = mock(RagEmbeddingStateService.class);
        ragSearchCache = mock(RagSearchCache.class);
        ragAskService = mock(RagAskService.class);
        userService = mock(UserService.class);
        ObjectProvider<RagIndexService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        RagController controller = new RagController();
        ReflectionTestUtils.setField(controller, "ragProperties", ragProperties);
        ReflectionTestUtils.setField(controller, "searchProperties", searchProperties);
        ReflectionTestUtils.setField(controller, "embeddingClient", new RecordingEmbeddingClient());
        ReflectionTestUtils.setField(controller, "embeddingText", new EmbeddingText());
        ReflectionTestUtils.setField(controller, "ragEmbeddingStateService", ragEmbeddingStateService);
        ReflectionTestUtils.setField(controller, "ragSearchCache", ragSearchCache);
        ReflectionTestUtils.setField(controller, "ragIndexServiceProvider", provider);
        ReflectionTestUtils.setField(controller, "ragAskService", ragAskService);
        ReflectionTestUtils.setField(controller, "userService", userService);
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler(
                        new PictureMetrics(new SimpleMeterRegistry())))
                .setValidator(validator)
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
        ReflectionTestUtils.setField(controller, "searchProperties", searchProperties);
        ReflectionTestUtils.setField(controller, "embeddingClient", new RecordingEmbeddingClient());
        ReflectionTestUtils.setField(controller, "embeddingText", new EmbeddingText());
        ReflectionTestUtils.setField(controller, "ragEmbeddingStateService", ragEmbeddingStateService);
        ReflectionTestUtils.setField(controller, "ragSearchCache", ragSearchCache);
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
        ReflectionTestUtils.setField(controller, "searchProperties", searchProperties);
        ReflectionTestUtils.setField(controller, "embeddingClient", new RecordingEmbeddingClient());
        ReflectionTestUtils.setField(controller, "embeddingText", new EmbeddingText());
        ReflectionTestUtils.setField(controller, "ragEmbeddingStateService", ragEmbeddingStateService);
        ReflectionTestUtils.setField(controller, "ragSearchCache", ragSearchCache);
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

        verify(ragSearchCache).clear();
    }

    @Test
    @DisplayName("前端配置：关闭时 hybrid / ask 都为 false，仍返回公开限额")
    void config_disabledReturnsAllOff() throws Exception {
        mockMvc.perform(get("/rag/config").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.enabled").value(false))
                .andExpect(jsonPath("$.data.hybridSearch").value(false))
                .andExpect(jsonPath("$.data.askEnabled").value(false))
                .andExpect(jsonPath("$.data.askDailyLimit").value(10))
                .andExpect(jsonPath("$.data.askTopK").value(8));
    }

    @Test
    @DisplayName("前端配置：RAG + ES 同时开启时 hybrid / ask 可用")
    void config_enabledWithElasticsearchReturnsHybridAndAsk() throws Exception {
        ragProperties.setEnabled(true);
        ragProperties.getAsk().setDailyLimit(5);
        ragProperties.getAsk().setTopK(6);
        searchProperties.setEngine("es");

        mockMvc.perform(get("/rag/config").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.enabled").value(true))
                .andExpect(jsonPath("$.data.hybridSearch").value(true))
                .andExpect(jsonPath("$.data.askEnabled").value(true))
                .andExpect(jsonPath("$.data.askDailyLimit").value(5))
                .andExpect(jsonPath("$.data.askTopK").value(6));
    }

    @Test
    @DisplayName("前端配置：RAG 开启但搜索仍是 MySQL 时不能 hybrid / ask")
    void config_enabledWithoutElasticsearchKeepsFeaturesOff() throws Exception {
        ragProperties.setEnabled(true);
        searchProperties.setEngine("mysql");

        mockMvc.perform(get("/rag/config").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.enabled").value(true))
                .andExpect(jsonPath("$.data.hybridSearch").value(false))
                .andExpect(jsonPath("$.data.askEnabled").value(false));
    }

    @Test
    @DisplayName("问答：登录用户 id 传给服务，返回回答与引用")
    void ask_returnsAnswerWithCitations() throws Exception {
        User loginUser = new User();
        loginUser.setId(7L);
        when(userService.getLoginUser(any())).thenReturn(loginUser);
        RagCitationVO citation = new RagCitationVO();
        citation.setId(12L);
        citation.setName("海边日落");
        citation.setThumbnailUrl("https://cos.example.com/12.jpg?imageMogr2/thumbnail/400x");
        citation.setReason("分类：风景；标签：海边、日落");
        when(ragAskService.ask(any(RagAskRequest.class), eq(7L)))
                .thenReturn(RagAskResponse.answered("推荐《海边日落》。", List.of(citation)));

        mockMvc.perform(post("/rag/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"哪些图适合做竖版海报？\"}")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.answer").value("推荐《海边日落》。"))
                .andExpect(jsonPath("$.data.refused").value(false))
                .andExpect(jsonPath("$.data.degraded").value(false))
                .andExpect(jsonPath("$.data.citations[0].id").value(12))
                .andExpect(jsonPath("$.data.citations[0].name").value("海边日落"));

        verify(ragAskService).ask(any(RagAskRequest.class), eq(7L));
    }

    @Test
    @DisplayName("问答：空问题返回 40000，不调用服务")
    void ask_blankQuestionReturnsParamsError() throws Exception {
        mockMvc.perform(post("/rag/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"   \"}")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40000));

        verify(ragAskService, never()).ask(any(), any());
    }

    @Test
    @DisplayName("问答：超限返回 42900")
    void ask_limitedReturnsTooManyRequests() throws Exception {
        User loginUser = new User();
        loginUser.setId(7L);
        when(userService.getLoginUser(any())).thenReturn(loginUser);
        when(ragAskService.ask(any(RagAskRequest.class), eq(7L)))
                .thenThrow(new BusinessException(ErrorCode.TOO_MANY_REQUESTS, "今日次数已用完"));

        mockMvc.perform(post("/rag/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"还有哪些图？\"}")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(42900));
    }
}

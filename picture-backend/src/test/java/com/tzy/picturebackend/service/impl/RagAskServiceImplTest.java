package com.tzy.picturebackend.service.impl;

import com.tzy.picturebackend.config.RagProperties;
import com.tzy.picturebackend.exception.BusinessException;
import com.tzy.picturebackend.exception.ErrorCode;
import com.tzy.picturebackend.manager.RagAskCache;
import com.tzy.picturebackend.manager.rag.RagAnswerException;
import com.tzy.picturebackend.manager.rag.RagAnswerGenerator;
import com.tzy.picturebackend.manager.rag.RagAnswerPayload;
import com.tzy.picturebackend.manager.rag.RagAskPromptBuilder;
import com.tzy.picturebackend.metrics.PictureMetrics;
import com.tzy.picturebackend.model.dto.rag.RagAskRequest;
import com.tzy.picturebackend.model.entity.Picture;
import com.tzy.picturebackend.model.enums.PictureReviewStatusEnum;
import com.tzy.picturebackend.model.vo.rag.RagAskResponse;
import com.tzy.picturebackend.service.PictureService;
import com.tzy.picturebackend.service.RagIndexService;
import com.tzy.picturebackend.service.RagSearchService;
import com.tzy.picturebackend.support.InMemoryRedis;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 图库问答核心逻辑：引用硬校验、无候选不调用模型、隐私过滤、限额、缓存与降级。
 * <p>
 * Elasticsearch、MySQL、Redis、DeepSeek 全部用 Mockito / 内存实现替代，不联网。
 */
class RagAskServiceImplTest {

    private RagSearchService ragSearchService;

    private PictureService pictureService;

    private RagIndexService ragIndexService;

    private StubGenerator generator;

    private RagProperties ragProperties;

    private SimpleMeterRegistry meterRegistry;

    private PictureMetrics pictureMetrics;

    private RagAskServiceImpl service;

    private Picture sunset;

    private Picture coffee;

    private Picture pending;

    @BeforeEach
    void setUp() {
        ragSearchService = mock(RagSearchService.class);
        pictureService = mock(PictureService.class);
        ragIndexService = mock(RagIndexService.class);
        generator = new StubGenerator();
        ragProperties = new RagProperties();
        meterRegistry = new SimpleMeterRegistry();
        pictureMetrics = new PictureMetrics(meterRegistry);

        sunset = picture(12L, "海边日落", "风景", "[\"海边\",\"日落\"]",
                1200, 1800, PictureReviewStatusEnum.PASS.getValue());
        coffee = picture(35L, "咖啡店一角", "商业", "[\"咖啡\",\"暖色调\"]",
                1600, 1200, PictureReviewStatusEnum.PASS.getValue());
        pending = picture(99L, "待审核秘密图", "人物", "[\"私密\"]",
                800, 800, PictureReviewStatusEnum.REVIEWING.getValue());

        when(ragSearchService.enabled()).thenReturn(true);
        when(ragSearchService.askCandidates(anyString(), anyInt())).thenReturn(List.of(12L, 35L));
        when(ragIndexService.indexName()).thenReturn("picture_vec_v2");
        when(pictureService.listByIds(any())).thenAnswer(invocation -> {
            Collection<?> ids = invocation.getArgument(0);
            return Stream.of(sunset, coffee, pending)
                    .filter(picture -> ids.contains(picture.getId()))
                    .toList();
        });
        generator.next = new RagAnswerPayload("推荐《海边日落》和《咖啡店一角》。", List.of(12L, 35L), false);
        service = newService(InMemoryRedis.createTemplate(new ConcurrentHashMap<>()));
    }

    @Test
    @DisplayName("正常回答：引用来自召回集，理由与缩略图由代码补齐")
    void ask_answeredWithCitations() {
        RagAskResponse response = service.ask(request("哪些图适合做竖版海报？"), 7L);

        assertFalse(response.isRefused());
        assertFalse(response.isDegraded());
        assertEquals("推荐《海边日落》和《咖啡店一角》。", response.getAnswer());
        assertEquals(2, response.getCitations().size());
        assertEquals(12L, response.getCitations().get(0).getId());
        assertTrue(response.getCitations().get(0).getReason().contains("风景"));
        assertTrue(response.getCitations().get(0).getReason().contains("竖版"));
        assertEquals("https://cos.example.com/12.jpg", response.getCitations().get(0).getPictureUrl());
        assertTrue(response.getCitations().get(0).getThumbnailUrl().contains("imageMogr2/thumbnail/400x"));
        assertEquals(1.0, counter(PictureMetrics.RAG_ASK, "result", "answered"));
        assertEquals(1, generator.calls);
    }

    @Test
    @DisplayName("无候选：直接拒答，不调用生成模型")
    void ask_noCandidatesRefusesWithoutGeneration() {
        when(ragSearchService.askCandidates(anyString(), anyInt())).thenReturn(List.of());

        RagAskResponse response = service.ask(request("图库里有没有火星照片？"), 7L);

        assertTrue(response.isRefused());
        assertFalse(response.isDegraded());
        assertEquals(RagAskResponse.ANSWER_NOT_FOUND, response.getAnswer());
        assertTrue(response.getCitations().isEmpty());
        assertEquals(0, generator.calls);
        assertEquals(1.0, counter(PictureMetrics.RAG_ASK, "result", "refused"));
    }

    @Test
    @DisplayName("引用越界：模型返回候选之外的 id，整条拒绝且不泄露模型文本")
    void ask_refusesCitationOutsideCandidates() {
        generator.next = new RagAnswerPayload("我发现了《待审核秘密图》。", List.of(12L, 99L), false);

        RagAskResponse response = service.ask(request("有哪些图片？"), 7L);

        assertTrue(response.isRefused());
        assertFalse(response.isDegraded());
        assertEquals(RagAskResponse.ANSWER_NOT_FOUND, response.getAnswer());
        assertTrue(response.getCitations().isEmpty());
        assertEquals(1.0, counter(PictureMetrics.RAG_ASK, "result", "invalid_citation"));
    }

    @Test
    @DisplayName("没有引用：非拒答却没有任何引用，视为无效回答")
    void ask_refusesWhenAnswerHasNoCitation() {
        generator.next = new RagAnswerPayload("图库里有不少好看的图片。", List.of(), false);

        RagAskResponse response = service.ask(request("有哪些图片？"), 7L);

        assertTrue(response.isRefused());
        assertEquals(1.0, counter(PictureMetrics.RAG_ASK, "result", "invalid_citation"));
    }

    @Test
    @DisplayName("拒答却带引用：引用必须为空，否则整条无效")
    void ask_refusesWhenRefusedButHasCitations() {
        generator.next = new RagAnswerPayload("没有找到", List.of(12L), true);

        RagAskResponse response = service.ask(request("有哪些图片？"), 7L);

        assertTrue(response.isRefused());
        assertEquals(1.0, counter(PictureMetrics.RAG_ASK, "result", "invalid_citation"));
    }

    @Test
    @DisplayName("回答超长：超过 max-answer-chars 整条拒绝")
    void ask_refusesWhenAnswerTooLong() {
        generator.next = new RagAnswerPayload("长".repeat(401), List.of(12L), false);

        RagAskResponse response = service.ask(request("有哪些图片？"), 7L);

        assertTrue(response.isRefused());
        assertEquals(1.0, counter(PictureMetrics.RAG_ASK, "result", "invalid_citation"));
    }

    @Test
    @DisplayName("生成失败：degraded=true + 固定文案，HTTP 层仍为 200")
    void ask_degradesWhenGenerationFails() {
        generator.failure = RagAnswerException.retryable("DeepSeek 问答请求超时");

        RagAskResponse response = service.ask(request("有哪些图片？"), 7L);

        assertTrue(response.isRefused());
        assertTrue(response.isDegraded());
        assertEquals(RagAskResponse.ANSWER_UNAVAILABLE, response.getAnswer());
        assertTrue(response.getCitations().isEmpty());
        assertEquals(1.0, counter(PictureMetrics.RAG_ASK, "result", "error"));
        assertEquals(1.0, counter(PictureMetrics.RAG_DEGRADED, "reason", "ask_timeout"));
    }

    @Test
    @DisplayName("检索失败：degraded=true，不返回 5xx")
    void ask_degradesWhenSearchFails() {
        when(ragSearchService.askCandidates(anyString(), anyInt()))
                .thenThrow(new IllegalStateException("es down"));

        RagAskResponse response = service.ask(request("有哪些图片？"), 7L);

        assertTrue(response.isDegraded());
        assertEquals(1.0, counter(PictureMetrics.RAG_DEGRADED, "reason", "ask_error"));
        assertEquals(0, generator.calls);
    }

    @Test
    @DisplayName("每日限额：超过后抛 42900，不调用模型")
    void ask_enforcesDailyLimit() {
        ragProperties.getAsk().setDailyLimit(1);
        assertFalse(service.ask(request("哪些图适合做海报？"), 7L).isRefused());

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.ask(request("再问一次"), 7L));

        assertEquals(ErrorCode.TOO_MANY_REQUESTS.getCode(), e.getCode());
        assertEquals(1, generator.calls, "第二次请求在限额处就被拦截");
        assertEquals(1.0, counter(PictureMetrics.RAG_ASK, "result", "limited"));
    }

    @Test
    @DisplayName("缓存：同一问题第二次不再召回 / 生成")
    void ask_usesAnswerCache() {
        RagAskResponse first = service.ask(request("哪些图适合做海报？"), 7L);
        RagAskResponse second = service.ask(request("哪些图适合做海报？"), 7L);

        assertEquals(first.getAnswer(), second.getAnswer());
        assertEquals(2, second.getCitations().size());
        assertEquals(1, generator.calls);
        verify(ragSearchService, times(1)).askCandidates(anyString(), anyInt());
    }

    @Test
    @DisplayName("缓存：索引名变化（重建切别名）后旧答案失效")
    void ask_cacheInvalidatedWhenIndexChanges() {
        when(ragIndexService.indexName()).thenReturn("picture_vec_v2", "picture_vec_v3");

        service.ask(request("哪些图适合做海报？"), 7L);
        service.ask(request("哪些图适合做海报？"), 7L);

        assertEquals(2, generator.calls);
    }

    @Test
    @DisplayName("隐私：未过审图片从候选中剔除，绝不进入提示词")
    void ask_filtersUnapprovedCandidatesFromPrompt() {
        when(ragSearchService.askCandidates(anyString(), anyInt())).thenReturn(List.of(12L, 99L));
        generator.next = new RagAnswerPayload("推荐《海边日落》。", List.of(12L), false);

        RagAskResponse response = service.ask(request("有哪些图片？"), 7L);

        assertFalse(response.isRefused());
        assertTrue(generator.lastUserPrompt.contains("海边日落"));
        assertFalse(generator.lastUserPrompt.contains("待审核秘密图"));
        assertEquals(List.of(12L), response.getCitations().stream().map(c -> c.getId()).toList());
    }

    @Test
    @DisplayName("Redis 故障：限额无法计数时降级，不放过无上限调用")
    @SuppressWarnings("unchecked")
    void ask_degradesWhenRedisFails() {
        StringRedisTemplate failingTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(failingTemplate.opsForValue()).thenReturn(ops);
        when(ops.increment(anyString())).thenThrow(new RuntimeException("redis down"));
        service = newService(failingTemplate);

        RagAskResponse response = service.ask(request("有哪些图片？"), 7L);

        assertTrue(response.isDegraded());
        assertEquals(0, generator.calls);
    }

    @Test
    @DisplayName("问题归一化：去控制字符、压缩空白")
    void normalizeQuestion_collapsesWhitespace() {
        assertEquals("哪些 图 适合", RagAskServiceImpl.normalizeQuestion("  哪些\n图\t适合  "));
        assertEquals("", RagAskServiceImpl.normalizeQuestion(null));
    }

    private RagAskServiceImpl newService(StringRedisTemplate redisTemplate) {
        RagAskCache cache = new RagAskCache(redisTemplate, new com.fasterxml.jackson.databind.ObjectMapper(),
                ragProperties);
        return new RagAskServiceImpl(ragSearchService, pictureService, ragIndexService,
                generator, new RagAskPromptBuilder(), cache, redisTemplate,
                ragProperties, pictureMetrics);
    }

    private static RagAskRequest request(String question) {
        RagAskRequest request = new RagAskRequest();
        request.setQuestion(question);
        return request;
    }

    private double counter(String name, String tagKey, String tagValue) {
        Counter counter = meterRegistry.find(name).tag(tagKey, tagValue).counter();
        assertNotNull(counter, "缺少指标 " + name + "{" + tagKey + "=" + tagValue + "}");
        return counter.count();
    }

    private static Picture picture(Long id, String name, String category, String tags,
                                   int width, int height, int reviewStatus) {
        Picture picture = new Picture();
        picture.setId(id);
        picture.setName(name);
        picture.setCategory(category);
        picture.setTags(tags);
        picture.setPicWidth(width);
        picture.setPicHeight(height);
        picture.setReviewStatus(reviewStatus);
        picture.setUrl("https://cos.example.com/" + id + ".jpg");
        picture.setIntroduction("画面描述：" + name);
        return picture;
    }

    /**
     * 可编程生成器：记录调用次数与最后一次用户提示词，按需返回或抛错
     */
    private static final class StubGenerator implements RagAnswerGenerator {

        private int calls;

        private String lastUserPrompt;

        private RagAnswerPayload next;

        private RuntimeException failure;

        @Override
        public RagAnswerPayload generate(String systemPrompt, String userPrompt) {
            calls++;
            lastUserPrompt = userPrompt;
            if (failure != null) {
                throw failure;
            }
            return next;
        }

        @Override
        public String model() {
            return "deepseek-flash";
        }
    }
}

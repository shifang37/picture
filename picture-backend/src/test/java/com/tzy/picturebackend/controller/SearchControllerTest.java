package com.tzy.picturebackend.controller;

import com.tzy.picturebackend.exception.ErrorCode;
import com.tzy.picturebackend.exception.GlobalExceptionHandler;
import com.tzy.picturebackend.manager.PictureListCache;
import com.tzy.picturebackend.metrics.PictureMetrics;
import com.tzy.picturebackend.model.vo.SearchHotKeywordVO;
import com.tzy.picturebackend.model.vo.SearchHotVO;
import com.tzy.picturebackend.model.vo.SearchNameSuggestionVO;
import com.tzy.picturebackend.model.vo.SearchSuggestVO;
import com.tzy.picturebackend.service.PictureSearchService;
import com.tzy.picturebackend.service.SearchMetaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
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
 * 搜索接口：索引重建、搜索联想与热词
 */
class SearchControllerTest {

    private MockMvc mockMvc;

    private PictureSearchService pictureSearchService;

    private PictureListCache pictureListCache;

    private SearchMetaService searchMetaService;

    private PictureMetrics pictureMetrics;

    @BeforeEach
    void setUp() {
        pictureSearchService = mock(PictureSearchService.class);
        pictureListCache = mock(PictureListCache.class);
        searchMetaService = mock(SearchMetaService.class);
        pictureMetrics = mock(PictureMetrics.class);
        SearchController searchController = new SearchController();
        ReflectionTestUtils.setField(searchController, "pictureSearchService", pictureSearchService);
        ReflectionTestUtils.setField(searchController, "pictureListCache", pictureListCache);
        ReflectionTestUtils.setField(searchController, "searchMetaService", searchMetaService);
        ReflectionTestUtils.setField(searchController, "pictureMetrics", pictureMetrics);
        mockMvc = MockMvcBuilders.standaloneSetup(searchController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("重建：返回写入索引的图片数量")
    void reindex_returnsIndexedCount() throws Exception {
        when(pictureSearchService.enabled()).thenReturn(true);
        when(pictureSearchService.reindexAll()).thenReturn(12);

        mockMvc.perform(post("/search/reindex"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").value(12));

        verify(pictureSearchService).reindexAll();
        verify(pictureListCache).invalidate();
    }

    @Test
    @DisplayName("重建：MySQL 搜索模式下返回操作失败，不执行重建")
    void reindex_rejectsWhenSearchDisabled() throws Exception {
        when(pictureSearchService.enabled()).thenReturn(false);

        mockMvc.perform(post("/search/reindex"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ErrorCode.OPERATION_ERROR.getCode()))
                .andExpect(jsonPath("$.message").value("未启用 Elasticsearch 搜索"));

        verify(pictureSearchService, never()).reindexAll();
        verify(pictureListCache, never()).invalidate();
    }

    @Test
    @DisplayName("联想：裁剪首尾空白后查询，并把 limit 收敛到 20")
    void suggest_returnsGroupedResult() throws Exception {
        SearchSuggestVO suggestions = new SearchSuggestVO();
        suggestions.setNames(List.of(new SearchNameSuggestionVO(1L, "日落海边")));
        suggestions.setTags(List.of("日落"));
        suggestions.setCategories(List.of("日落风景"));
        when(searchMetaService.suggest("日落", 20)).thenReturn(suggestions);

        mockMvc.perform(get("/search/suggest").param("q", " 日落 ").param("limit", "99"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.names[0].id").value("1"))
                .andExpect(jsonPath("$.data.names[0].name").value("日落海边"))
                .andExpect(jsonPath("$.data.tags[0]").value("日落"))
                .andExpect(jsonPath("$.data.categories[0]").value("日落风景"));

        verify(searchMetaService).suggest("日落", 20);
        verify(pictureMetrics).searchMeta("suggest");
    }

    @Test
    @DisplayName("联想：空搜索词返回参数错误，不查库")
    void suggest_rejectsBlankKeyword() throws Exception {
        mockMvc.perform(get("/search/suggest").param("q", "  "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ErrorCode.PARAMS_ERROR.getCode()));

        verify(searchMetaService, never()).suggest(anyString(), anyInt());
        verify(pictureMetrics, never()).searchMeta("suggest");
    }

    @Test
    @DisplayName("联想：超过 32 字符返回参数错误")
    void suggest_rejectsTooLongKeyword() throws Exception {
        mockMvc.perform(get("/search/suggest").param("q", "图".repeat(33)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ErrorCode.PARAMS_ERROR.getCode()));

        verify(searchMetaService, never()).suggest(anyString(), anyInt());
    }

    @Test
    @DisplayName("热词：默认 10 条，返回标签与分类")
    void hot_returnsKeywordsWithDefaultLimit() throws Exception {
        SearchHotVO hot = new SearchHotVO();
        hot.setTags(List.of(new SearchHotKeywordVO("风景", 12L)));
        hot.setCategories(List.of(new SearchHotKeywordVO("城市", 8L)));
        when(searchMetaService.hotKeywords(10)).thenReturn(hot);

        mockMvc.perform(get("/search/hot"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.tags[0].name").value("风景"))
                .andExpect(jsonPath("$.data.tags[0].count").value(12))
                .andExpect(jsonPath("$.data.categories[0].name").value("城市"));

        verify(pictureMetrics).searchMeta(eq("hot"));
    }
}

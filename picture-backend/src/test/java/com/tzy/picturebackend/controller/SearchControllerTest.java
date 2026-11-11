package com.tzy.picturebackend.controller;

import com.tzy.picturebackend.exception.ErrorCode;
import com.tzy.picturebackend.exception.GlobalExceptionHandler;
import com.tzy.picturebackend.manager.PictureListCache;
import com.tzy.picturebackend.service.PictureSearchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 搜索运维接口：全量重建的返回值与未启用时的错误码
 */
class SearchControllerTest {

    private MockMvc mockMvc;

    private PictureSearchService pictureSearchService;

    private PictureListCache pictureListCache;

    @BeforeEach
    void setUp() {
        pictureSearchService = mock(PictureSearchService.class);
        pictureListCache = mock(PictureListCache.class);
        SearchController searchController = new SearchController();
        ReflectionTestUtils.setField(searchController, "pictureSearchService", pictureSearchService);
        ReflectionTestUtils.setField(searchController, "pictureListCache", pictureListCache);
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
}

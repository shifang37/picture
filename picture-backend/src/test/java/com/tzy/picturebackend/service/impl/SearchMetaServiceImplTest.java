package com.tzy.picturebackend.service.impl;

import com.tzy.picturebackend.exception.BusinessException;
import com.tzy.picturebackend.mapper.PictureMapper;
import com.tzy.picturebackend.model.vo.SearchHotKeywordVO;
import com.tzy.picturebackend.model.vo.SearchHotVO;
import com.tzy.picturebackend.model.vo.SearchNameSuggestionVO;
import com.tzy.picturebackend.model.vo.SearchSuggestVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 搜索元数据服务测试：联想结果归一化、条数收敛与空结果处理
 */
class SearchMetaServiceImplTest {

    private SearchMetaServiceImpl searchMetaService;

    private PictureMapper pictureMapper;

    @BeforeEach
    void setUp() {
        searchMetaService = new SearchMetaServiceImpl();
        pictureMapper = mock(PictureMapper.class);
        ReflectionTestUtils.setField(searchMetaService, "pictureMapper", pictureMapper);
    }

    // ==================== 搜索联想 ====================

    @Test
    @DisplayName("联想：空白搜索词直接返回参数错误，不查库")
    void suggest_rejectsBlankKeyword() {
        assertThrows(BusinessException.class, () -> searchMetaService.suggest("   ", 10));
        verify(pictureMapper, never()).selectNameSuggestions(anyString(), anyInt());
    }

    @Test
    @DisplayName("联想：超过 32 字符返回参数错误")
    void suggest_rejectsTooLongKeyword() {
        String keyword = "图".repeat(33);
        BusinessException e = assertThrows(BusinessException.class,
                () -> searchMetaService.suggest(keyword, 10));
        assertEquals(40000, e.getCode());
        verify(pictureMapper, never()).selectNameSuggestions(anyString(), anyInt());
    }

    @Test
    @DisplayName("联想：三组结果按公开口径查询，并把条数收敛到上限 20")
    void suggest_normalizesLimitAndQueries() {
        when(pictureMapper.selectNameSuggestions("日落", 20))
                .thenReturn(List.of(new SearchNameSuggestionVO(1L, "日落海边")));
        when(pictureMapper.selectTagSuggestions("日落", 20)).thenReturn(List.of("日落"));
        when(pictureMapper.selectCategorySuggestions("日落", 20)).thenReturn(List.of("日落风景"));

        SearchSuggestVO result = searchMetaService.suggest("日落", 100);

        assertEquals(1, result.getNames().size());
        assertEquals(1L, result.getNames().get(0).getId());
        assertEquals(List.of("日落"), result.getTags());
        assertEquals(List.of("日落风景"), result.getCategories());
    }

    @Test
    @DisplayName("联想：limit 为空或非正数时回落到默认 10 条")
    void suggest_fallsBackToDefaultLimit() {
        when(pictureMapper.selectNameSuggestions("山", 10)).thenReturn(List.of());
        when(pictureMapper.selectTagSuggestions("山", 10)).thenReturn(List.of());
        when(pictureMapper.selectCategorySuggestions("山", 10)).thenReturn(List.of());

        searchMetaService.suggest("山", null);
        searchMetaService.suggest("山", 0);

        verify(pictureMapper, org.mockito.Mockito.times(2)).selectNameSuggestions("山", 10);
    }

    @Test
    @DisplayName("联想：同名图片只保留一条，空名称被丢弃")
    void suggest_dedupesNames() {
        when(pictureMapper.selectNameSuggestions("日", 10)).thenReturn(List.of(
                new SearchNameSuggestionVO(1L, "日落"),
                new SearchNameSuggestionVO(2L, "日落"),
                new SearchNameSuggestionVO(3L, null),
                new SearchNameSuggestionVO(4L, "  "),
                new SearchNameSuggestionVO(5L, "日照")));
        when(pictureMapper.selectTagSuggestions("日", 10)).thenReturn(null);
        when(pictureMapper.selectCategorySuggestions("日", 10)).thenReturn(null);

        SearchSuggestVO result = searchMetaService.suggest("日", 10);

        assertEquals(2, result.getNames().size());
        // 保留排序最靠前的那条（id 更小的先出现）
        assertEquals(1L, result.getNames().get(0).getId());
        assertEquals("日照", result.getNames().get(1).getName());
        // mapper 返回 null 时也要给出空列表，前端不需要判空
        assertTrue(result.getTags().isEmpty());
        assertTrue(result.getCategories().isEmpty());
    }

    // ==================== 热门搜索词 ====================

    @Test
    @DisplayName("热词：标签与分类分别聚合，limit 收敛到上限")
    void hotKeywords_returnsAggregatedKeywords() {
        when(pictureMapper.selectHotTags(20))
                .thenReturn(List.of(new SearchHotKeywordVO("风景", 12L)));
        when(pictureMapper.selectHotCategories(20))
                .thenReturn(List.of(new SearchHotKeywordVO("城市", 8L)));

        SearchHotVO result = searchMetaService.hotKeywords(50);

        assertEquals(1, result.getTags().size());
        assertEquals("风景", result.getTags().get(0).getName());
        assertEquals(12L, result.getTags().get(0).getCount());
        assertEquals("城市", result.getCategories().get(0).getName());
    }

    @Test
    @DisplayName("热词：没有公开图片时返回空列表而不是 null")
    void hotKeywords_returnsEmptyLists() {
        when(pictureMapper.selectHotTags(10)).thenReturn(null);
        when(pictureMapper.selectHotCategories(10)).thenReturn(null);

        SearchHotVO result = searchMetaService.hotKeywords(null);

        assertTrue(result.getTags().isEmpty());
        assertTrue(result.getCategories().isEmpty());
    }
}

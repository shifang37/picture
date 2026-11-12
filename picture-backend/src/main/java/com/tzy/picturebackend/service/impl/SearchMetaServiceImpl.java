package com.tzy.picturebackend.service.impl;

import cn.hutool.core.util.StrUtil;
import com.tzy.picturebackend.config.CacheConfig;
import com.tzy.picturebackend.exception.ErrorCode;
import com.tzy.picturebackend.exception.ThrowUtils;
import com.tzy.picturebackend.mapper.PictureMapper;
import com.tzy.picturebackend.model.vo.SearchHotKeywordVO;
import com.tzy.picturebackend.model.vo.SearchHotVO;
import com.tzy.picturebackend.model.vo.SearchNameSuggestionVO;
import com.tzy.picturebackend.model.vo.SearchSuggestVO;
import com.tzy.picturebackend.service.SearchMetaService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 搜索元数据服务实现。
 * <p>
 * 只读 MySQL、只做前缀匹配：{@code name LIKE 'kw%'} 能命中 {@code idx_name}，
 * 而 {@code LIKE '%kw%'} 会退化成全表扫描。热词统计按标签 / 分类分组计数，
 * 结果放在 Redis（{@code searchMeta}，TTL 10 分钟），空结果同样缓存以防穿透。
 */
@Service
@Slf4j
public class SearchMetaServiceImpl implements SearchMetaService {

    @Resource
    private PictureMapper pictureMapper;

    @Override
    @Cacheable(cacheNames = CacheConfig.SEARCH_META_CACHE, key = "'suggest:' + #keyword + ':' + #limit")
    public SearchSuggestVO suggest(String keyword, Integer limit) {
        ThrowUtils.throwIf(StrUtil.isBlank(keyword), ErrorCode.PARAMS_ERROR, "搜索词不能为空");
        String normalized = keyword.trim();
        ThrowUtils.throwIf(normalized.length() > MAX_KEYWORD_LENGTH, ErrorCode.PARAMS_ERROR,
                "搜索词不能超过 " + MAX_KEYWORD_LENGTH + " 个字符");
        int size = normalizeLimit(limit);

        SearchSuggestVO result = new SearchSuggestVO();
        result.setNames(dedupeNames(pictureMapper.selectNameSuggestions(normalized, size)));
        result.setTags(nullSafeText(pictureMapper.selectTagSuggestions(normalized, size)));
        result.setCategories(nullSafeText(pictureMapper.selectCategorySuggestions(normalized, size)));
        return result;
    }

    @Override
    @Cacheable(cacheNames = CacheConfig.SEARCH_META_CACHE, key = "'hot:' + #limit")
    public SearchHotVO hotKeywords(Integer limit) {
        int size = normalizeLimit(limit);
        SearchHotVO result = new SearchHotVO();
        result.setTags(nullSafe(pictureMapper.selectHotTags(size)));
        result.setCategories(nullSafe(pictureMapper.selectHotCategories(size)));
        return result;
    }

    /**
     * 条数收敛到 1–{@link #MAX_LIMIT}；null 或非法值回落到默认值
     */
    private int normalizeLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    /**
     * 同名图片在联想里只保留排序最靠前的一条：名称重复展示没有意义，
     * 反而会挤掉其它可用建议（命中顺序由 SQL 的「完全匹配优先、名称更短优先」决定）。
     */
    private List<SearchNameSuggestionVO> dedupeNames(List<SearchNameSuggestionVO> suggestions) {
        if (suggestions == null || suggestions.isEmpty()) {
            return new ArrayList<>();
        }
        Map<String, SearchNameSuggestionVO> unique = new LinkedHashMap<>();
        for (SearchNameSuggestionVO suggestion : suggestions) {
            if (suggestion == null || StrUtil.isBlank(suggestion.getName())) {
                continue;
            }
            unique.putIfAbsent(suggestion.getName().toLowerCase(Locale.ROOT), suggestion);
        }
        return new ArrayList<>(unique.values());
    }

    private List<SearchHotKeywordVO> nullSafe(List<SearchHotKeywordVO> keywords) {
        return keywords == null ? new ArrayList<>() : keywords;
    }

    private List<String> nullSafeText(List<String> values) {
        return values == null ? new ArrayList<>() : values;
    }
}

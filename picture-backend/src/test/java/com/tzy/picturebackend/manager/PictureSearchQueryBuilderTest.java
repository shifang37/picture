package com.tzy.picturebackend.manager;

import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.MultiMatchQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Operator;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import com.tzy.picturebackend.model.dto.picture.PictureQueryRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 查询条件到 Elasticsearch 查询的翻译：全文检索、精确过滤、分页与排序
 */
class PictureSearchQueryBuilderTest {

    private final PictureSearchQueryBuilder queryBuilder = new PictureSearchQueryBuilder();

    @Test
    @DisplayName("搜索词：名称权重更高，标签/分类/格式等条件进入 filter")
    void build_fullTextSearchAndFilters() {
        PictureQueryRequest request = new PictureQueryRequest();
        request.setSearchText("日落 海边");
        request.setCurrent(2);
        request.setPageSize(10);
        request.setCategory("风景");
        request.setTags(List.of("海边", "日落"));
        request.setPicFormat("jpeg");
        request.setUserId(5L);
        request.setReviewStatus(1);

        SearchRequest searchRequest = queryBuilder.build(request, "picture");

        assertEquals(List.of("picture"), searchRequest.index());
        assertEquals(10, searchRequest.from());
        assertEquals(10, searchRequest.size());
        assertTrue(Boolean.TRUE.equals(searchRequest.trackTotalHits().enabled()));

        Query query = searchRequest.query();
        assertTrue(query.isBool());
        assertEquals(1, query.bool().must().size());
        MultiMatchQuery multiMatch = query.bool().must().get(0).multiMatch();
        assertEquals("日落 海边", multiMatch.query());
        assertEquals(List.of("name^3", "introduction"), multiMatch.fields());
        assertEquals(Operator.Or, multiMatch.operator());

        // category、两个 tag、picFormat、userId、reviewStatus
        assertEquals(6, query.bool().filter().size());
        Set<String> filterFields = query.bool().filter().stream()
                .map(filter -> filter.term().field())
                .collect(Collectors.toSet());
        assertEquals(Set.of("category", "tags", "picFormat", "userId", "reviewStatus"), filterFields);

        // 有搜索词时按相关度排序，再按创建时间和 id 稳定分页
        assertEquals(3, searchRequest.sort().size());
        assertTrue(searchRequest.sort().get(0).isScore());
        assertEquals(SortOrder.Desc, searchRequest.sort().get(0).score().order());
        assertEquals("createTime", searchRequest.sort().get(1).field().field());
        assertEquals("id", searchRequest.sort().get(2).field().field());
    }

    @Test
    @DisplayName("无搜索词：按请求的排序字段排序，名称使用 keyword 子字段")
    void build_sortsByRequestedField() {
        PictureQueryRequest request = new PictureQueryRequest();
        request.setSortField("name");
        request.setSortOrder("ascend");
        request.setReviewStatus(1);

        SearchRequest searchRequest = queryBuilder.build(request, "picture");

        assertEquals("name.keyword", searchRequest.sort().get(0).field().field());
        assertEquals(SortOrder.Asc, searchRequest.sort().get(0).field().order());
        assertEquals("id", searchRequest.sort().get(1).field().field());
    }

    @Test
    @DisplayName("无搜索词、无排序：默认按创建时间倒序")
    void build_defaultsToCreateTimeDesc() {
        PictureQueryRequest request = new PictureQueryRequest();
        request.setReviewStatus(1);

        SearchRequest searchRequest = queryBuilder.build(request, "picture");

        assertEquals(2, searchRequest.sort().size());
        assertEquals("createTime", searchRequest.sort().get(0).field().field());
        assertEquals(SortOrder.Desc, searchRequest.sort().get(0).field().order());
        assertEquals("id", searchRequest.sort().get(1).field().field());
    }

    @Test
    @DisplayName("没有过滤条件时使用 match_all，避免生成空 bool 查询")
    void build_usesMatchAllWhenNoCondition() {
        PictureQueryRequest request = new PictureQueryRequest();

        SearchRequest searchRequest = queryBuilder.build(request, "picture");

        assertTrue(searchRequest.query().isMatchAll());
        assertFalse(searchRequest.query().isBool());
    }

    @Test
    @DisplayName("非法排序字段抛出异常，由调用方回落到 MySQL 并返回参数错误")
    void build_rejectsUnknownSortField() {
        PictureQueryRequest request = new PictureQueryRequest();
        request.setSortField("password");

        assertThrows(PictureSearchSortException.class,
                () -> queryBuilder.build(request, "picture"));
    }
}

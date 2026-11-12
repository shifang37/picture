package com.tzy.picturebackend.manager;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import co.elastic.clients.elasticsearch._types.SortOptions;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Operator;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import com.tzy.picturebackend.model.dto.picture.PictureQueryRequest;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 把公开列表的查询条件翻译成 Elasticsearch 查询。
 * <p>
 * 与 MySQL 查询的对应关系：
 * <ul>
 *   <li>名称、简介的模糊匹配改为全文检索，名称权重更高（{@code name^3}）；</li>
 *   <li>分类、标签、格式、用户、尺寸等精确条件放进 {@code filter}，不参与打分；</li>
 *   <li>有搜索词时按相关度排序，否则按请求的排序字段或创建时间倒序；</li>
 *   <li>排序字段沿用 MySQL 的白名单，非法字段直接抛出 {@link IllegalArgumentException}，
 *       由调用方回落到 MySQL 查询，从而保持原有的参数错误响应。</li>
 * </ul>
 */
@Component
public class PictureSearchQueryBuilder {

    /**
     * 公开列表允许排序的字段 → Elasticsearch 字段。
     * <p>
     * 与 {@code PictureServiceImpl.SORT_FIELD_WHITELIST} 保持一致；
     * 名称使用 keyword 子字段排序，避免分词后无法排序。
     */
    static final Map<String, String> SORT_FIELDS = Map.ofEntries(
            Map.entry("id", "id"),
            Map.entry("name", "name.keyword"),
            Map.entry("category", "category"),
            Map.entry("picSize", "picSize"),
            Map.entry("picWidth", "picWidth"),
            Map.entry("picHeight", "picHeight"),
            Map.entry("picScale", "picScale"),
            Map.entry("picFormat", "picFormat"),
            Map.entry("userId", "userId"),
            Map.entry("createTime", "createTime"),
            Map.entry("editTime", "editTime"),
            Map.entry("updateTime", "updateTime"),
            Map.entry("reviewStatus", "reviewStatus"),
            Map.entry("reviewTime", "reviewTime"),
            Map.entry("machineReviewStatus", "machineReviewStatus"),
            // 热度排序：与 MySQL 的 SORT_FIELD_WHITELIST 保持一致
            Map.entry("downloadCount", "downloadCount"),
            Map.entry("favoriteCount", "favoriteCount"),
            Map.entry("likeCount", "likeCount"));

    /**
     * 构造一次公开列表查询；索引通过别名传入，便于以后切换索引版本
     */
    public SearchRequest build(PictureQueryRequest request, String indexName) {
        List<Query> must = new ArrayList<>();
        List<Query> filter = new ArrayList<>();
        String searchText = request.getSearchText();

        if (StrUtil.isNotBlank(searchText)) {
            String keyword = searchText.trim();
            must.add(Query.of(q -> q.multiMatch(m -> m
                    .query(keyword)
                    .fields("name^3", "introduction")
                    .operator(Operator.Or))));
        }
        if (StrUtil.isNotBlank(request.getName())) {
            String name = request.getName().trim();
            must.add(Query.of(q -> q.match(m -> m.field("name").query(name))));
        }
        if (StrUtil.isNotBlank(request.getIntroduction())) {
            String introduction = request.getIntroduction().trim();
            must.add(Query.of(q -> q.match(m -> m.field("introduction").query(introduction))));
        }

        addTerm(filter, "id", request.getId() == null ? null : String.valueOf(request.getId()));
        addTerm(filter, "userId", request.getUserId());
        addTerm(filter, "category", request.getCategory());
        addTerm(filter, "picFormat", request.getPicFormat());
        addTerm(filter, "picSize", request.getPicSize());
        addTerm(filter, "picWidth", request.getPicWidth());
        addTerm(filter, "picHeight", request.getPicHeight());
        addTerm(filter, "picScale", request.getPicScale());
        addTerm(filter, "reviewStatus", request.getReviewStatus());
        if (CollUtil.isNotEmpty(request.getTags())) {
            // 多个标签是「同时包含」关系，与 MySQL 的 picture_tag JOIN + HAVING 语义一致
            for (String tag : request.getTags()) {
                if (StrUtil.isNotBlank(tag)) {
                    addTerm(filter, "tags", tag.trim());
                }
            }
        }

        BoolQuery.Builder boolQuery = new BoolQuery.Builder();
        if (!must.isEmpty()) {
            boolQuery.must(must);
        }
        if (!filter.isEmpty()) {
            boolQuery.filter(filter);
        }
        Query query = must.isEmpty() && filter.isEmpty()
                ? Query.of(q -> q.matchAll(m -> m))
                : Query.of(q -> q.bool(boolQuery.build()));

        long fromLong = Math.max(0, ((long) request.getCurrent() - 1) * request.getPageSize());
        SearchRequest.Builder builder = new SearchRequest.Builder()
                .index(indexName)
                .from(Math.toIntExact(fromLong))
                .size(Math.toIntExact(request.getPageSize()))
                // 只召回 id，不需要 _source；total 用于分页
                .source(source -> source.fetch(false))
                .trackTotalHits(t -> t.enabled(true))
                .query(query);
        builder.sort(sortOptions(request));
        return builder.build();
    }

    /**
     * 排序规则：有搜索词时按相关度；否则按请求字段（默认创建时间倒序）。
     * <p>
     * 统一追加 id 作为第二排序键，保证分页顺序稳定。
     */
    private List<SortOptions> sortOptions(PictureQueryRequest request) {
        List<SortOptions> sorts = new ArrayList<>();
        if (StrUtil.isNotBlank(request.getSearchText())) {
            sorts.add(SortOptions.of(s -> s.score(sc -> sc.order(SortOrder.Desc))));
            sorts.add(fieldSort("createTime", SortOrder.Desc));
            sorts.add(fieldSort("id", SortOrder.Asc));
            return sorts;
        }
        String sortField = request.getSortField();
        if (StrUtil.isBlank(sortField)) {
            sorts.add(fieldSort("createTime", SortOrder.Desc));
            sorts.add(fieldSort("id", SortOrder.Asc));
            return sorts;
        }
        String esField = SORT_FIELDS.get(sortField);
        if (esField == null) {
            throw new PictureSearchSortException("不支持的排序字段: " + sortField);
        }
        SortOrder order = "ascend".equals(request.getSortOrder()) ? SortOrder.Asc : SortOrder.Desc;
        sorts.add(fieldSort(esField, order));
        if (!"id".equals(esField)) {
            sorts.add(fieldSort("id", SortOrder.Asc));
        }
        return sorts;
    }

    private static SortOptions fieldSort(String field, SortOrder order) {
        return SortOptions.of(s -> s.field(f -> f.field(field).order(order)));
    }

    private static void addTerm(List<Query> filter, String field, String value) {
        if (StrUtil.isBlank(value)) {
            return;
        }
        filter.add(Query.of(q -> q.term(t -> t.field(field).value(value))));
    }

    private static void addTerm(List<Query> filter, String field, Long value) {
        if (value == null) {
            return;
        }
        filter.add(Query.of(q -> q.term(t -> t.field(field).value(value))));
    }

    private static void addTerm(List<Query> filter, String field, Integer value) {
        if (value == null) {
            return;
        }
        filter.add(Query.of(q -> q.term(t -> t.field(field).value(value))));
    }

    private static void addTerm(List<Query> filter, String field, Double value) {
        if (value == null) {
            return;
        }
        filter.add(Query.of(q -> q.term(t -> t.field(field).value(value))));
    }

}

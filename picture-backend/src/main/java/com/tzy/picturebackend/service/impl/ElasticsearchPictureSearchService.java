package com.tzy.picturebackend.service.impl;

import cn.hutool.core.collection.CollUtil;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.indices.update_aliases.Action;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tzy.picturebackend.manager.PictureSearchQueryBuilder;
import com.tzy.picturebackend.model.dto.picture.PictureQueryRequest;
import com.tzy.picturebackend.model.entity.Picture;
import com.tzy.picturebackend.model.enums.PictureReviewStatusEnum;
import com.tzy.picturebackend.model.search.PictureSearchDocument;
import com.tzy.picturebackend.service.PictureSearchService;
import com.tzy.picturebackend.service.PictureService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Elasticsearch 搜索实现。
 * <p>
 * 索引 {@code picture_v1} 通过别名 {@code picture} 对外提供服务：写入、删除、查询都走别名，
 * 以后修改映射时可以在新索引上重建再原子切换别名。
 * <p>
 * 全量重建会先把数据写入临时索引，再在一个 {@code _aliases} 请求里切换别名，期间旧索引继续可查，
 * 因此重建不会出现「搜索为空」的窗口。
 */
@Service
@ConditionalOnProperty(prefix = "app.search", name = "engine", havingValue = "es")
@Slf4j
public class ElasticsearchPictureSearchService implements PictureSearchService {

    /**
     * 当前索引名；修改映射时升级为 picture_v2，并通过别名切换
     */
    static final String INDEX_NAME = "picture_v1";

    /**
     * 对外查询与写入使用的别名
     */
    static final String INDEX_ALIAS = "picture";

    /**
     * 全量重建使用的临时索引前缀
     */
    static final String REBUILD_INDEX_PREFIX = "picture_rebuild_";

    /**
     * 全量重建每批读取的图片数
     */
    static final int REINDEX_BATCH_SIZE = 500;

    private final ElasticsearchClient elasticsearchClient;

    private final PictureSearchQueryBuilder queryBuilder;

    private final PictureService pictureService;

    /**
     * 索引与别名是否已确认存在；为空时每次操作前检查一次，避免 ES 重启后索引丢失
     */
    private final AtomicBoolean indexReady = new AtomicBoolean(false);

    public ElasticsearchPictureSearchService(ElasticsearchClient elasticsearchClient,
                                             PictureSearchQueryBuilder queryBuilder,
                                             PictureService pictureService) {
        this.elasticsearchClient = elasticsearchClient;
        this.queryBuilder = queryBuilder;
        this.pictureService = pictureService;
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public void indexPicture(Picture picture) {
        if (picture == null || picture.getId() == null) {
            return;
        }
        if (!Objects.equals(picture.getReviewStatus(), PictureReviewStatusEnum.PASS.getValue())) {
            // 防御性处理：不可公开的图片绝不能写入索引
            deletePicture(picture.getId());
            return;
        }
        ensureIndex();
        PictureSearchDocument document = PictureSearchDocument.from(picture);
        try {
            elasticsearchClient.index(index -> index
                    .index(INDEX_ALIAS)
                    .id(document.getId())
                    .document(document));
        } catch (IOException e) {
            throw new IllegalStateException("写入搜索索引失败, pictureId=" + picture.getId(), e);
        }
    }

    @Override
    public void deletePicture(Long pictureId) {
        if (pictureId == null) {
            return;
        }
        ensureIndex();
        try {
            elasticsearchClient.delete(delete -> delete
                    .index(INDEX_ALIAS)
                    .id(String.valueOf(pictureId)));
        } catch (ElasticsearchException e) {
            if (e.status() == 404) {
                // 文档本来就不存在（重复消息、从未入库），按成功处理
                log.debug("搜索索引中不存在该文档，忽略删除, pictureId={}", pictureId);
                return;
            }
            throw new IllegalStateException("删除搜索索引失败, pictureId=" + pictureId, e);
        } catch (IOException e) {
            throw new IllegalStateException("删除搜索索引失败, pictureId=" + pictureId, e);
        }
    }

    @Override
    public Page<Long> searchPictureIds(PictureQueryRequest request) {
        ensureIndex();
        SearchRequest searchRequest = queryBuilder.build(request, INDEX_ALIAS);
        try {
            SearchResponse<Void> response = elasticsearchClient.search(searchRequest, Void.class);
            List<Long> ids = new ArrayList<>();
            for (Hit<Void> hit : response.hits().hits()) {
                Long id = parseId(hit.id());
                if (id != null) {
                    ids.add(id);
                }
            }
            long total = response.hits().total() == null
                    ? ids.size()
                    : response.hits().total().value();
            Page<Long> page = new Page<>(request.getCurrent(), request.getPageSize(), total);
            page.setRecords(ids);
            return page;
        } catch (IOException e) {
            throw new IllegalStateException("执行搜索失败", e);
        }
    }

    @Override
    public synchronized int reindexAll() {
        ensureIndex();
        String rebuildIndex = REBUILD_INDEX_PREFIX + System.currentTimeMillis();
        try {
            createIndex(rebuildIndex);
        } catch (IOException e) {
            throw new IllegalStateException("创建重建索引失败", e);
        }
        try {
            int indexed = copyPublicPictures(rebuildIndex);
            // 刷新后文档立即可查，别名切换后无需等待 refresh 间隔
            elasticsearchClient.indices().refresh(refresh -> refresh.index(rebuildIndex));
            switchAlias(rebuildIndex);
            log.info("搜索索引重建完成: {} 条公开图片写入 {}", indexed, INDEX_ALIAS);
            return indexed;
        } catch (IOException | RuntimeException e) {
            // 重建失败时清理临时索引，旧索引与别名保持不变
            deleteIndexQuietly(rebuildIndex);
            throw new IllegalStateException("重建搜索索引失败", e);
        }
    }

    @Override
    public long countIndexed() {
        ensureIndex();
        try {
            return elasticsearchClient.count(count -> count.index(INDEX_ALIAS)).count();
        } catch (IOException e) {
            throw new IllegalStateException("统计搜索索引文档数失败", e);
        }
    }

    /**
     * 确保索引与别名存在。ES 重启后索引通常仍在，这里只做一次存在性检查；
     * 多实例同时启动时依赖 ES 的幂等报错与二次检查兜底。
     */
    private void ensureIndex() {
        if (indexReady.get()) {
            return;
        }
        synchronized (indexReady) {
            if (indexReady.get()) {
                return;
            }
            try {
                boolean aliasExists = elasticsearchClient.indices()
                        .exists(exists -> exists.index(INDEX_ALIAS))
                        .value();
                if (!aliasExists) {
                    boolean indexExists = elasticsearchClient.indices()
                            .exists(exists -> exists.index(INDEX_NAME))
                            .value();
                    if (!indexExists) {
                        createIndex(INDEX_NAME);
                    }
                    addAlias(INDEX_NAME);
                }
                indexReady.set(true);
            } catch (IOException e) {
                throw new IllegalStateException("初始化搜索索引失败", e);
            }
        }
    }

    /**
     * 创建索引与显式映射：名称、简介使用内置 cjk 分析器，其余字段用 keyword / 数值类型。
     * <p>
     * 不引入 IK 插件，避免自建镜像；cjk 按二元切分，对中文搜索已够用。
     */
    private void createIndex(String indexName) throws IOException {
        elasticsearchClient.indices().create(create -> create
                .index(indexName)
                .settings(settings -> settings
                        .numberOfShards("1")
                        .numberOfReplicas("0"))
                .mappings(mapping -> mapping
                        .properties("id", property -> property.keyword(keyword -> keyword))
                        .properties("name", property -> property.text(text -> text
                                .analyzer("cjk")
                                .fields("keyword", keyword -> keyword.keyword(inner -> inner.ignoreAbove(256)))))
                        .properties("introduction", property -> property.text(text -> text.analyzer("cjk")))
                        .properties("category", property -> property.keyword(keyword -> keyword))
                        .properties("tags", property -> property.keyword(keyword -> keyword))
                        .properties("picFormat", property -> property.keyword(keyword -> keyword))
                        .properties("userId", property -> property.long_(number -> number))
                        .properties("picSize", property -> property.long_(number -> number))
                        .properties("downloadCount", property -> property.long_(number -> number))
                        .properties("favoriteCount", property -> property.long_(number -> number))
                        .properties("likeCount", property -> property.long_(number -> number))
                        .properties("picWidth", property -> property.integer(number -> number))
                        .properties("picHeight", property -> property.integer(number -> number))
                        .properties("picScale", property -> property.float_(number -> number))
                        .properties("createTime", property -> property.date(date -> date))
                        .properties("editTime", property -> property.date(date -> date))
                        .properties("updateTime", property -> property.date(date -> date))
                        .properties("reviewStatus", property -> property.integer(number -> number))
                        .properties("reviewTime", property -> property.date(date -> date))
                        .properties("machineReviewStatus", property -> property.integer(number -> number))));
    }

    /**
     * 给索引添加别名；多实例并发首次启动时，另一个实例可能已经创建了别名
     */
    private void addAlias(String indexName) throws IOException {
        try {
            elasticsearchClient.indices().updateAliases(update -> update
                    .actions(action -> action.add(add -> add.index(indexName).alias(INDEX_ALIAS))));
            log.info("已为搜索索引 {} 创建别名 {}", indexName, INDEX_ALIAS);
        } catch (ElasticsearchException e) {
            boolean aliasExists = elasticsearchClient.indices()
                    .existsAlias(exists -> exists.name(INDEX_ALIAS))
                    .value();
            if (!aliasExists) {
                throw e;
            }
            log.debug("搜索索引别名已存在，跳过创建");
        }
    }

    /**
     * 分批把 MySQL 中已通过审核的图片写入临时索引
     */
    private int copyPublicPictures(String indexName) throws IOException {
        int total = 0;
        long lastId = 0L;
        while (true) {
            List<Picture> batch = pictureService.list(new QueryWrapper<Picture>()
                    .eq("reviewStatus", PictureReviewStatusEnum.PASS.getValue())
                    .gt("id", lastId)
                    .orderByAsc("id")
                    .last("LIMIT " + REINDEX_BATCH_SIZE));
            if (CollUtil.isEmpty(batch)) {
                break;
            }
            bulkIndex(indexName, batch);
            total += batch.size();
            lastId = batch.get(batch.size() - 1).getId();
            if (batch.size() < REINDEX_BATCH_SIZE) {
                break;
            }
        }
        return total;
    }

    private void bulkIndex(String indexName, List<Picture> pictures) throws IOException {
        List<BulkOperation> operations = pictures.stream()
                .map(picture -> BulkOperation.of(operation -> operation.index(index -> index
                        .index(indexName)
                        .id(String.valueOf(picture.getId()))
                        .document(PictureSearchDocument.from(picture)))))
                .toList();
        BulkResponse response = elasticsearchClient.bulk(bulk -> bulk.operations(operations));
        if (response.errors()) {
            for (BulkResponseItem item : response.items()) {
                if (item.error() != null) {
                    log.error("写入搜索索引失败, id={}, reason={}", item.id(), item.error().reason());
                }
            }
            throw new IllegalStateException("批量写入搜索索引失败");
        }
    }

    /**
     * 原子切换别名到新索引，成功后删除旧索引
     */
    private void switchAlias(String newIndex) throws IOException {
        Set<String> oldIndices = indicesWithAlias();
        List<Action> actions = new ArrayList<>();
        for (String oldIndex : oldIndices) {
            if (!oldIndex.equals(newIndex)) {
                actions.add(Action.of(action -> action.remove(remove -> remove
                        .index(oldIndex)
                        .alias(INDEX_ALIAS))));
            }
        }
        actions.add(Action.of(action -> action.add(add -> add
                .index(newIndex)
                .alias(INDEX_ALIAS)
                .isWriteIndex(true))));
        elasticsearchClient.indices().updateAliases(update -> update.actions(actions));
        indexReady.set(true);
        for (String oldIndex : oldIndices) {
            if (!oldIndex.equals(newIndex)) {
                deleteIndexQuietly(oldIndex);
            }
        }
    }

    private Set<String> indicesWithAlias() throws IOException {
        boolean aliasExists = elasticsearchClient.indices()
                .existsAlias(exists -> exists.name(INDEX_ALIAS))
                .value();
        if (!aliasExists) {
            return Set.of();
        }
        return elasticsearchClient.indices()
                .getAlias(alias -> alias.name(INDEX_ALIAS))
                .result()
                .keySet();
    }

    private void deleteIndexQuietly(String indexName) {
        try {
            elasticsearchClient.indices().delete(delete -> delete.index(indexName));
        } catch (ElasticsearchException e) {
            if (e.status() != 404) {
                log.warn("删除搜索索引失败, index={}", indexName, e);
            }
        } catch (IOException e) {
            log.warn("删除搜索索引失败, index={}", indexName, e);
        }
    }

    private static Long parseId(String id) {
        if (!StringUtils.hasText(id)) {
            return null;
        }
        try {
            return Long.valueOf(id);
        } catch (NumberFormatException e) {
            log.warn("搜索索引中存在非法图片 id, id={}", id);
            return null;
        }
    }
}

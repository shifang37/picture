package com.tzy.picturebackend.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.mapping.DenseVectorIndexOptionsType;
import co.elastic.clients.elasticsearch._types.mapping.DenseVectorSimilarity;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import co.elastic.clients.elasticsearch.indices.GetAliasResponse;
import co.elastic.clients.elasticsearch.indices.GetMappingResponse;
import co.elastic.clients.elasticsearch.indices.GetIndexResponse;
import co.elastic.clients.elasticsearch.indices.get_mapping.IndexMappingRecord;
import co.elastic.clients.elasticsearch.indices.update_aliases.Action;
import co.elastic.clients.elasticsearch._types.mapping.Property;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.tzy.picturebackend.config.RagProperties;
import com.tzy.picturebackend.manager.rag.EmbeddingClient;
import com.tzy.picturebackend.manager.rag.EmbeddingException;
import com.tzy.picturebackend.manager.rag.EmbeddingText;
import com.tzy.picturebackend.manager.rag.RagImageLoader;
import com.tzy.picturebackend.metrics.PictureMetrics;
import com.tzy.picturebackend.model.entity.Picture;
import com.tzy.picturebackend.model.entity.PictureEmbedding;
import com.tzy.picturebackend.model.enums.EmbeddingStatusEnum;
import com.tzy.picturebackend.model.enums.PictureReviewStatusEnum;
import com.tzy.picturebackend.model.vo.rag.RagReindexVO;
import com.tzy.picturebackend.service.PictureService;
import com.tzy.picturebackend.service.RagEmbeddingStateService;
import com.tzy.picturebackend.service.RagIndexService;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Elasticsearch 向量索引实现。
 * <p>
 * 与 BM25 索引完全隔离：独立索引 {@code picture_vec_v*} + 别名 {@code picture_vec}，
 * 删除索引即可回滚，不影响现有搜索。
 * <ul>
 *   <li>写入前守卫：别名必须指向 {@code picture_vec_v<数字>} 且维度与配置一致，否则拒绝写入；</li>
 *   <li>增量写入：文本/图片 hash 与模型版本都未变化时跳过向量化，只做必要的文档更新；</li>
 *   <li>全量重建：写入下一个版本的索引，成功后原子切换别名并删除旧索引；</li>
 *   <li>失败处理：单条失败写 {@code picture_embedding.lastError}，重建有失败时不切别名。</li>
 * </ul>
 */
@Slf4j
public class ElasticsearchRagIndexService implements RagIndexService {

    /**
     * 向量索引名前缀；别名只允许指向这个前缀的索引
     */
    static final String INDEX_PREFIX = "picture_vec_v";

    /**
     * 索引名后缀必须是数字，保证「picture_vec_v1」「picture_vec_v2」可以按版本递增
     */
    private static final Pattern INDEX_NAME_PATTERN = Pattern.compile("^picture_vec_v(\\d+)$");

    /**
     * 全量重建每批读取的图片数
     */
    static final int REINDEX_BATCH_SIZE = 200;

    private final ElasticsearchClient elasticsearchClient;

    private final EmbeddingClient embeddingClient;

    private final EmbeddingText embeddingText;

    private final RagProperties ragProperties;

    private final RagEmbeddingStateService stateService;

    private final RagImageLoader imageLoader;

    private final PictureService pictureService;

    private final PictureMetrics pictureMetrics;

    /**
     * 已确认可写的别名目标；为空表示尚未解析或索引/别名需要重建
     */
    private String resolvedIndex;

    /**
     * 别名指向了不兼容的索引，禁止写入
     */
    private boolean stale;

    public ElasticsearchRagIndexService(ElasticsearchClient elasticsearchClient,
                                        EmbeddingClient embeddingClient,
                                        EmbeddingText embeddingText,
                                        RagProperties ragProperties,
                                        RagEmbeddingStateService stateService,
                                        RagImageLoader imageLoader,
                                        PictureService pictureService,
                                        PictureMetrics pictureMetrics) {
        this.elasticsearchClient = elasticsearchClient;
        this.embeddingClient = embeddingClient;
        this.embeddingText = embeddingText;
        this.ragProperties = ragProperties;
        this.stateService = stateService;
        this.imageLoader = imageLoader;
        this.pictureService = pictureService;
        this.pictureMetrics = pictureMetrics;
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public synchronized void indexPicture(Picture picture) {
        if (picture == null || picture.getId() == null) {
            return;
        }
        Long pictureId = picture.getId();
        if (!Objects.equals(picture.getReviewStatus(), PictureReviewStatusEnum.PASS.getValue())) {
            // 防御性处理：不可公开的图片绝不能写入索引
            deletePicture(pictureId);
            return;
        }
        String text = embeddingText.build(picture);
        boolean hasText = StrUtil.isNotBlank(text);
        String textHash = embeddingText.hash(text);
        String imageHash = imageHash(picture);
        PictureEmbedding state = stateService.get(pictureId);

        String textModel = embeddingClient.model();
        String textVersion = embeddingText.version();
        String imageModel = ragProperties.getEmbedding().getImageModel();
        String imageVersion = embeddingClient.version();

        boolean textFresh = isFresh(state, true, textHash, textModel, textVersion);
        boolean imageFresh = isFresh(state, false, imageHash, imageModel, imageVersion);
        if (textFresh && imageFresh) {
            pictureMetrics.ragIndex("write", "skip");
            log.debug("向量未变化，跳过写入, pictureId={}", pictureId);
            return;
        }

        String target = writeTarget();
        if (target == null) {
            // 别名不可用时已经在 writeTarget 里记录 stale/fail 指标，这里不再写坏索引
            return;
        }

        boolean textAttempted = hasText && !textFresh;
        boolean imageAttempted = !imageFresh;
        try {
            float[] textVector = textAttempted ? embedText(text) : null;
            float[] imageVector = imageAttempted ? embedImage(picture) : null;
            Map<String, Object> document = document(picture, textHash, imageHash, textVector, imageVector);
            // 写已校验的具体索引而不是别名：即使别名被外部改指向 BM25 索引，也不会写坏它
            upsert(target, pictureId, document);
            if (!textFresh) {
                // 语料为空的图片也要把文本侧记为成功，否则待处理数永远不归零
                stateService.markTextSuccess(pictureId, textHash, textModel, textVersion);
            }
            if (imageAttempted) {
                stateService.markImageSuccess(pictureId, imageHash, imageModel, imageVersion);
            }
            pictureMetrics.ragIndex("write", "ok");
            log.info("向量索引写入完成, pictureId={}, text={}, image={}",
                    pictureId, textAttempted, imageAttempted);
        } catch (RuntimeException e) {
            String message = errorMessage(e);
            stateService.markFailure(pictureId, textAttempted, imageAttempted, message);
            pictureMetrics.ragIndex("write", "fail");
            throw new IllegalStateException("写入向量索引失败, pictureId=" + pictureId + ": " + message, e);
        }
    }

    @Override
    public synchronized void deletePicture(Long pictureId) {
        if (pictureId == null) {
            return;
        }
        // 状态行先删：即使 ES 暂时不可用，MySQL 侧也不会留下「已成功」的陈旧状态
        stateService.delete(pictureId);
        String target = currentTarget();
        if (target == null) {
            pictureMetrics.ragIndex("delete", stale ? "stale" : "skip");
            return;
        }
        try {
            elasticsearchClient.delete(delete -> delete
                    .index(target)
                    .id(String.valueOf(pictureId)));
            pictureMetrics.ragIndex("delete", "ok");
        } catch (ElasticsearchException e) {
            if (e.status() == 404) {
                // 文档本来就不存在（重复消息、从未入库），按成功处理
                log.debug("向量索引中不存在该文档，忽略删除, pictureId={}", pictureId);
                pictureMetrics.ragIndex("delete", "skip");
                return;
            }
            pictureMetrics.ragIndex("delete", "fail");
            throw new IllegalStateException("删除向量索引失败, pictureId=" + pictureId, e);
        } catch (IOException e) {
            pictureMetrics.ragIndex("delete", "fail");
            throw new IllegalStateException("删除向量索引失败, pictureId=" + pictureId, e);
        }
    }

    @Override
    public synchronized long countIndexed() {
        String target = currentTarget();
        if (target == null) {
            return 0L;
        }
        try {
            return elasticsearchClient.count(count -> count
                    .index(target)).count();
        } catch (ElasticsearchException e) {
            if (e.status() == 404) {
                // 索引被外部删除：下次写入会重新创建
                resolvedIndex = null;
                return 0L;
            }
            throw new IllegalStateException("统计向量索引文档数失败", e);
        } catch (IOException e) {
            throw new IllegalStateException("统计向量索引文档数失败", e);
        }
    }

    @Override
    public synchronized RagReindexVO reindexAll() {
        RagReindexVO result = new RagReindexVO();
        // 先解析旧别名，重建期间旧索引继续可查；解析失败只影响「旧索引是否可删」，不阻断重建
        currentTarget();
        String target = nextIndexName();
        try {
            createIndex(target);
        } catch (IOException e) {
            pictureMetrics.ragIndex("reindex", "fail");
            throw new IllegalStateException("创建向量重建索引失败", e);
        }

        List<SuccessMark> successMarks = new ArrayList<>();
        int indexed = 0;
        int skipped = 0;
        int failed = 0;
        long lastId = 0L;
        boolean aborted = false;
        try {
            while (true) {
                List<Picture> batch = loadPublicPictures(lastId);
                if (CollUtil.isEmpty(batch)) {
                    break;
                }
                lastId = batch.get(batch.size() - 1).getId();

                List<IngestPlan> plans = buildPlans(batch);
                int batchSkipped = (int) plans.stream().filter(plan -> !plan.hasText()).count();
                skipped += batchSkipped;

                float[][] textVectors = embedTextsForReindex(plans);
                if (textVectors == null) {
                    // 文本向量整批失败：记录本批失败并停止，旧索引保持不变
                    for (IngestPlan plan : plans) {
                        if (plan.hasText()) {
                            stateService.markFailure(plan.picture().getId(), true, false,
                                    "全量重建：文本向量化失败");
                        }
                    }
                    failed += plans.size();
                    aborted = true;
                    break;
                }

                List<ImageOutcome> imageOutcomes = embedImagesForReindex(plans);
                int imageFailures = 0;
                for (int i = 0; i < plans.size(); i++) {
                    ImageOutcome outcome = imageOutcomes.get(i);
                    if (outcome.error() != null) {
                        IngestPlan plan = plans.get(i);
                        stateService.markFailure(plan.picture().getId(), plan.hasText(), true,
                                errorMessage(outcome.error()));
                        imageFailures++;
                    }
                }
                if (imageFailures > 0) {
                    failed += imageFailures;
                    aborted = true;
                    break;
                }

                List<Map<String, Object>> documents = new ArrayList<>(plans.size());
                List<SuccessMark> batchMarks = new ArrayList<>(plans.size());
                for (int i = 0; i < plans.size(); i++) {
                    IngestPlan plan = plans.get(i);
                    float[] textVector = textVectors[i];
                    float[] imageVector = imageOutcomes.get(i).vector();
                    documents.add(document(plan.picture(), plan.textHash(), plan.imageHash(),
                            textVector, imageVector));
                    batchMarks.add(new SuccessMark(plan.picture().getId(), plan.textHash(),
                            plan.imageHash(), plan.hasText()));
                }
                try {
                    bulkIndex(target, documents);
                } catch (IOException | RuntimeException e) {
                    for (IngestPlan plan : plans) {
                        stateService.markFailure(plan.picture().getId(), plan.hasText(), true,
                                errorMessage(e));
                    }
                    failed += plans.size();
                    aborted = true;
                    break;
                }
                indexed += documents.size();
                successMarks.addAll(batchMarks);
                log.info("向量重建进度: indexed={}, failed={}, target={}", indexed, failed, target);
                if (batch.size() < REINDEX_BATCH_SIZE) {
                    break;
                }
            }

            if (aborted) {
                // 有失败就不切别名：旧索引继续服务，失败图片由下一次重建或增量消息补齐
                deleteIndexQuietly(target);
                result.setIndexed(indexed);
                result.setSkipped(skipped);
                result.setFailed(failed);
                result.setSwitched(false);
                result.setMessage("有 " + failed + " 张图片失败，已保留旧索引；修复后可重新重建");
                pictureMetrics.ragIndex("reindex", "fail");
                log.warn("向量索引重建中止，保留旧索引: indexed={}, skipped={}, failed={}",
                        indexed, skipped, failed);
                return result;
            }

            elasticsearchClient.indices().refresh(refresh -> refresh.index(target));
            switchAlias(target);
            for (SuccessMark mark : successMarks) {
                stateService.markTextSuccess(mark.pictureId(), mark.textHash(),
                        embeddingClient.model(), embeddingText.version());
                stateService.markImageSuccess(mark.pictureId(), mark.imageHash(),
                        ragProperties.getEmbedding().getImageModel(), embeddingClient.version());
            }
            resolvedIndex = target;
            stale = false;
            result.setIndexed(indexed);
            result.setSkipped(skipped);
            result.setFailed(0);
            result.setSwitched(true);
            result.setMessage("重建完成");
            pictureMetrics.ragIndex("reindex", "ok");
            log.info("向量索引重建完成: indexed={}, skipped={}, index={}", indexed, skipped, target);
            return result;
        } catch (IOException e) {
            deleteIndexQuietly(target);
            pictureMetrics.ragIndex("reindex", "fail");
            throw new IllegalStateException("重建向量索引失败", e);
        } catch (RuntimeException e) {
            deleteIndexQuietly(target);
            pictureMetrics.ragIndex("reindex", "fail");
            throw e;
        }
    }

    @Override
    public synchronized String indexName() {
        String target = currentTarget();
        return target == null ? ragProperties.getIndex().getName() : target;
    }

    @Override
    public synchronized boolean stale() {
        return stale;
    }

    /**
     * 增量写入前拿到可用索引：别名存在且兼容时直接返回其目标；
     * 别名不存在时创建配置的初始索引与别名。
     *
     * @return 可写的具体索引名；别名不兼容或初始化失败时返回 null
     */
    private String writeTarget() {
        String target = currentTarget();
        if (target != null) {
            return target;
        }
        if (stale) {
            pictureMetrics.ragIndex("write", "stale");
            log.error("向量索引别名不兼容，拒绝写入；请检查别名 {} 是否指向 {}<数字> 且维度为 {}",
                    ragProperties.getIndex().getAlias(), INDEX_PREFIX, ragProperties.getIndex().getDims());
            return null;
        }
        String indexName = ragProperties.getIndex().getName();
        try {
            if (!indexExists(indexName)) {
                createIndex(indexName);
            }
            addAlias(indexName);
            resolvedIndex = indexName;
            stale = false;
            log.info("已为向量索引 {} 创建别名 {}", indexName, ragProperties.getIndex().getAlias());
            return indexName;
        } catch (IOException e) {
            pictureMetrics.ragIndex("write", "fail");
            throw new IllegalStateException("初始化向量索引失败", e);
        }
    }

    /**
     * 解析别名指向的索引并校验；结果会缓存，索引被外部删除时由写入/统计失败重置。
     */
    private String currentTarget() {
        if (resolvedIndex != null && !stale) {
            return resolvedIndex;
        }
        try {
            boolean aliasExists = elasticsearchClient.indices()
                    .existsAlias(exists -> exists.name(ragProperties.getIndex().getAlias()))
                    .value();
            if (!aliasExists) {
                resolvedIndex = null;
                stale = false;
                return null;
            }
            GetAliasResponse response = elasticsearchClient.indices()
                    .getAlias(alias -> alias.name(ragProperties.getIndex().getAlias()));
            Set<String> targets = response.result().keySet();
            if (targets.size() != 1) {
                markStale("别名应只指向一个索引，实际 " + targets);
                return null;
            }
            String target = targets.iterator().next();
            if (!isCompatible(target)) {
                markStale("索引名或维度不兼容: " + target);
                return null;
            }
            resolvedIndex = target;
            stale = false;
            return target;
        } catch (IOException e) {
            throw new IllegalStateException("读取向量索引别名失败", e);
        }
    }

    /**
     * 写前守卫：索引名必须是 picture_vec_v<数字>，且 captionVector 维度与配置一致。
     */
    private boolean isCompatible(String indexName) {
        if (!INDEX_NAME_PATTERN.matcher(indexName).matches()) {
            log.error("向量索引名不合法, index={}, expected={}<数字>", indexName, INDEX_PREFIX);
            return false;
        }
        try {
            GetMappingResponse response = elasticsearchClient.indices()
                    .getMapping(mapping -> mapping.index(indexName));
            IndexMappingRecord record = response.result().get(indexName);
            if (record == null || record.mappings() == null) {
                return false;
            }
            Property property = record.mappings().properties().get("captionVector");
            if (property == null || !property.isDenseVector()
                    || property.denseVector().dims() == null) {
                return false;
            }
            int actual = property.denseVector().dims();
            if (actual != ragProperties.getIndex().getDims()) {
                log.error("向量维度不一致, index={}, actual={}, expected={}",
                        indexName, actual, ragProperties.getIndex().getDims());
                return false;
            }
            return true;
        } catch (IOException e) {
            throw new IllegalStateException("读取向量索引映射失败, index=" + indexName, e);
        }
    }

    private void markStale(String reason) {
        stale = true;
        resolvedIndex = null;
        pictureMetrics.ragIndex("guard", "stale");
        log.error("向量索引写前守卫拒绝: {}, alias={}", reason, ragProperties.getIndex().getAlias());
    }

    /**
     * 下一版索引名：picture_vec_v<现有最大版本 + 1>；首次创建使用配置的初始名。
     */
    private String nextIndexName() {
        int maxVersion = 0;
        try {
            GetIndexResponse response = elasticsearchClient.indices()
                    .get(index -> index.index(INDEX_PREFIX + "*"));
            for (String name : response.result().keySet()) {
                Integer version = parseIndexVersion(name);
                if (version != null) {
                    maxVersion = Math.max(maxVersion, version);
                }
            }
        } catch (ElasticsearchException e) {
            if (e.status() != 404) {
                throw new IllegalStateException("读取向量索引列表失败", e);
            }
        } catch (IOException e) {
            throw new IllegalStateException("读取向量索引列表失败", e);
        }
        if (maxVersion == 0) {
            return ragProperties.getIndex().getName();
        }
        return INDEX_PREFIX + (maxVersion + 1);
    }

    /**
     * 读取一页公开图片，按 id 升序游标分页，避免深分页。
     */
    private List<Picture> loadPublicPictures(long lastId) {
        return pictureService.list(new QueryWrapper<Picture>()
                .eq("reviewStatus", PictureReviewStatusEnum.PASS.getValue())
                .gt("id", lastId)
                .orderByAsc("id")
                .last("LIMIT " + REINDEX_BATCH_SIZE));
    }

    private List<IngestPlan> buildPlans(List<Picture> pictures) {
        List<IngestPlan> plans = new ArrayList<>(pictures.size());
        for (Picture picture : pictures) {
            String text = embeddingText.build(picture);
            plans.add(new IngestPlan(picture, embeddingText.hash(text), imageHash(picture),
                    StrUtil.isNotBlank(text)));
        }
        return plans;
    }

    /**
     * 一次请求取回整批文本向量；返回 null 表示整批失败。
     */
    private float[][] embedTextsForReindex(List<IngestPlan> plans) {
        List<IngestPlan> textPlans = plans.stream().filter(IngestPlan::hasText).toList();
        float[][] vectors = new float[plans.size()][];
        if (textPlans.isEmpty()) {
            return vectors;
        }
        List<String> texts = textPlans.stream()
                .map(plan -> embeddingText.build(plan.picture()))
                .toList();
        try {
            List<float[]> embedded = embeddingClient.embedTexts(texts);
            if (embedded.size() != texts.size()) {
                throw new IllegalStateException("文本向量返回条数不一致: expected="
                        + texts.size() + ", actual=" + embedded.size());
            }
            int embeddedIndex = 0;
            for (int i = 0; i < plans.size(); i++) {
                if (!plans.get(i).hasText()) {
                    continue;
                }
                vectors[i] = validateVector(embedded.get(embeddedIndex++), "text");
            }
            return vectors;
        } catch (RuntimeException e) {
            log.warn("全量重建：文本向量化失败, size={}", texts.size(), e);
            return null;
        }
    }

    /**
     * 图片接口一次只能传一张（百炼限制 image batch size=[1,1]），重建时按配置并发。
     */
    private List<ImageOutcome> embedImagesForReindex(List<IngestPlan> plans) {
        int threads = Math.max(1, ragProperties.getIndex().getReindexThreads());
        ExecutorService executor = Executors.newFixedThreadPool(threads, runnable -> {
            Thread thread = new Thread(runnable, "rag-reindex-image");
            thread.setDaemon(true);
            return thread;
        });
        try {
            List<Future<float[]>> futures = new ArrayList<>(plans.size());
            for (IngestPlan plan : plans) {
                futures.add(executor.submit(() -> embedImage(plan.picture())));
            }
            List<ImageOutcome> outcomes = new ArrayList<>(plans.size());
            for (Future<float[]> future : futures) {
                try {
                    outcomes.add(new ImageOutcome(future.get(), null));
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    outcomes.add(new ImageOutcome(null, cause instanceof RuntimeException runtime
                            ? runtime : new IllegalStateException(cause)));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("向量重建被中断", e);
                }
            }
            return outcomes;
        } finally {
            executor.shutdownNow();
        }
    }

    private float[] embedText(String text) {
        List<float[]> vectors = embeddingClient.embedTexts(List.of(text));
        if (vectors.size() != 1) {
            throw EmbeddingException.permanent("文本向量返回条数不合法: " + vectors.size());
        }
        return validateVector(vectors.get(0), "text");
    }

    /**
     * 图片向量：默认 base64；URL 模式失败时回退 base64（规划要求两条路径都记 embed 指标）。
     */
    private float[] embedImage(Picture picture) {
        if (imageLoader.useUrlInput()) {
            try {
                return embedImageInput(imageLoader.buildUrlInput(picture));
            } catch (RuntimeException e) {
                log.warn("图片 URL 入口向量化失败，回退 base64, pictureId={}, message={}",
                        picture.getId(), errorMessage(e));
            }
        }
        return embedImageInput(imageLoader.buildBase64Input(picture));
    }

    private float[] embedImageInput(String imageInput) {
        List<float[]> vectors = embeddingClient.embedImages(List.of(imageInput));
        if (vectors.size() != 1) {
            throw EmbeddingException.permanent("图片向量返回条数不合法: " + vectors.size());
        }
        return validateVector(vectors.get(0), "image");
    }

    private float[] validateVector(float[] vector, String kind) {
        int dims = ragProperties.getIndex().getDims();
        if (vector == null || vector.length != dims) {
            throw EmbeddingException.permanent(kind + " 向量维度不合法: expected=" + dims
                    + ", actual=" + (vector == null ? "null" : vector.length));
        }
        return vector;
    }

    /**
     * 文档字段：只在本次新算了向量时才带向量与模型版本，未变化的字段靠 update 合并保留。
     */
    private Map<String, Object> document(Picture picture, String textHash, String imageHash,
                                         float[] textVector, float[] imageVector) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("pictureId", picture.getId());
        document.put("userId", picture.getUserId());
        document.put("reviewStatus", picture.getReviewStatus());
        document.put("category", picture.getCategory());
        List<String> tags = embeddingText.parseTags(picture.getTags());
        if (!tags.isEmpty()) {
            document.put("tags", tags);
        }
        document.put("updatedAt", Instant.now().toString());
        if (textVector != null) {
            document.put("captionVector", textVector);
            document.put("textModel", embeddingClient.model());
            document.put("textVersion", embeddingText.version());
            document.put("textHash", textHash);
        }
        if (imageVector != null) {
            document.put("imageVector", imageVector);
            document.put("imageModel", ragProperties.getEmbedding().getImageModel());
            document.put("imageVersion", embeddingClient.version());
            document.put("imageHash", imageHash);
        }
        return document;
    }

    /**
     * 局部更新 + doc_as_upsert：只更新本次变化的向量，未变化的一侧保留原值；
     * 文档不存在时按当前字段创建。
     */
    private void upsert(String indexName, Long pictureId, Map<String, Object> document) {
        try {
            elasticsearchClient.<Map<String, Object>, Map<String, Object>>update(update -> update
                    .index(indexName)
                    .id(String.valueOf(pictureId))
                    .doc(document)
                    .docAsUpsert(true)
                    .retryOnConflict(3), Map.class);
        } catch (ElasticsearchException e) {
            if (e.status() == 404) {
                // 索引被外部删除：清缓存，下一次消息会重新建索引
                resolvedIndex = null;
            }
            throw new IllegalStateException("写入向量文档失败, pictureId=" + pictureId, e);
        } catch (IOException e) {
            throw new IllegalStateException("写入向量文档失败, pictureId=" + pictureId, e);
        }
    }

    private void bulkIndex(String indexName, List<Map<String, Object>> documents) throws IOException {
        List<BulkOperation> operations = new ArrayList<>(documents.size());
        for (Map<String, Object> document : documents) {
            Object pictureId = document.get("pictureId");
            operations.add(BulkOperation.of(operation -> operation.index(index -> index
                    .index(indexName)
                    .id(String.valueOf(pictureId))
                    .document(document))));
        }
        BulkResponse response = elasticsearchClient.bulk(bulk -> bulk.operations(operations));
        if (response.errors()) {
            for (BulkResponseItem item : response.items()) {
                if (item.error() != null) {
                    log.error("向量文档批量写入失败, id={}, reason={}", item.id(), item.error().reason());
                }
            }
            throw new IllegalStateException("批量写入向量文档失败");
        }
    }

    private void createIndex(String indexName) throws IOException {
        if (indexExists(indexName)) {
            return;
        }
        int dims = ragProperties.getIndex().getDims();
        try {
            elasticsearchClient.indices().create(create -> create
                    .index(indexName)
                    .settings(settings -> settings
                            .numberOfShards("1")
                            .numberOfReplicas("0"))
                    .mappings(mapping -> mapping
                            .properties("pictureId", property -> property.keyword(keyword -> keyword))
                            .properties("userId", property -> property.long_(number -> number))
                            .properties("reviewStatus", property -> property.integer(number -> number))
                            .properties("category", property -> property.keyword(keyword -> keyword))
                            .properties("tags", property -> property.keyword(keyword -> keyword))
                            .properties("captionVector", property -> property.denseVector(vector -> vector
                                    .dims(dims)
                                    .index(true)
                                    .similarity(DenseVectorSimilarity.Cosine)
                                    .indexOptions(options -> options
                                            .type(DenseVectorIndexOptionsType.Hnsw)
                                            .m(16)
                                            .efConstruction(100))))
                            .properties("imageVector", property -> property.denseVector(vector -> vector
                                    .dims(dims)
                                    .index(true)
                                    .similarity(DenseVectorSimilarity.Cosine)
                                    .indexOptions(options -> options
                                            .type(DenseVectorIndexOptionsType.Hnsw)
                                            .m(16)
                                            .efConstruction(100))))
                            .properties("textModel", property -> property.keyword(keyword -> keyword))
                            .properties("textVersion", property -> property.keyword(keyword -> keyword))
                            .properties("textHash", property -> property.keyword(keyword -> keyword))
                            .properties("imageModel", property -> property.keyword(keyword -> keyword))
                            .properties("imageVersion", property -> property.keyword(keyword -> keyword))
                            .properties("imageHash", property -> property.keyword(keyword -> keyword))
                            .properties("updatedAt", property -> property.date(date -> date))));
        } catch (ElasticsearchException e) {
            if (e.error() != null && "resource_already_exists_exception".equals(e.error().type())) {
                log.debug("向量索引已存在，跳过创建, index={}", indexName);
                return;
            }
            throw e;
        }
        log.info("已创建向量索引 {}, dims={}", indexName, dims);
    }

    private void addAlias(String indexName) throws IOException {
        elasticsearchClient.indices().updateAliases(update -> update
                .actions(action -> action.add(add -> add
                        .index(indexName)
                        .alias(ragProperties.getIndex().getAlias()))));
    }

    /**
     * 原子切换别名到新索引，成功后删除旧索引。
     */
    private void switchAlias(String newIndex) throws IOException {
        Set<String> oldIndices = aliasTargets();
        List<Action> actions = new ArrayList<>();
        for (String oldIndex : oldIndices) {
            if (!oldIndex.equals(newIndex)) {
                actions.add(Action.of(action -> action.remove(remove -> remove
                        .index(oldIndex)
                        .alias(ragProperties.getIndex().getAlias()))));
            }
        }
        actions.add(Action.of(action -> action.add(add -> add
                .index(newIndex)
                .alias(ragProperties.getIndex().getAlias())
                .isWriteIndex(true))));
        elasticsearchClient.indices().updateAliases(update -> update.actions(actions));
        for (String oldIndex : oldIndices) {
            if (!oldIndex.equals(newIndex)) {
                deleteIndexQuietly(oldIndex);
            }
        }
    }

    private Set<String> aliasTargets() {
        try {
            boolean aliasExists = elasticsearchClient.indices()
                    .existsAlias(exists -> exists.name(ragProperties.getIndex().getAlias()))
                    .value();
            if (!aliasExists) {
                return Set.of();
            }
            return Set.copyOf(elasticsearchClient.indices()
                    .getAlias(alias -> alias.name(ragProperties.getIndex().getAlias()))
                    .result()
                    .keySet());
        } catch (IOException e) {
            throw new IllegalStateException("读取向量索引别名失败", e);
        }
    }

    private boolean indexExists(String indexName) throws IOException {
        return elasticsearchClient.indices().exists(exists -> exists.index(indexName)).value();
    }

    private void deleteIndexQuietly(String indexName) {
        try {
            elasticsearchClient.indices().delete(delete -> delete.index(indexName));
        } catch (ElasticsearchException e) {
            if (e.status() != 404) {
                log.warn("删除向量索引失败, index={}", indexName, e);
            }
        } catch (IOException e) {
            log.warn("删除向量索引失败, index={}", indexName, e);
        }
    }

    /**
     * 判断某侧的缓存是否仍然可用：状态成功、hash 一致、模型与版本都一致。
     */
    private boolean isFresh(PictureEmbedding state, boolean text, String hash,
                            String model, String version) {
        if (state == null) {
            return false;
        }
        Integer status = text ? state.getTextStatus() : state.getImageStatus();
        String storedHash = text ? state.getTextHash() : state.getImageHash();
        String storedModel = text ? state.getTextModel() : state.getImageModel();
        String storedVersion = text ? state.getTextVersion() : state.getImageVersion();
        return Objects.equals(status, EmbeddingStatusEnum.SUCCESS.getValue())
                && Objects.equals(storedHash, hash)
                && Objects.equals(storedModel, model)
                && Objects.equals(storedVersion, version);
    }

    /**
     * 图片内容 hash：优先复用秒传的 fileHash；历史数据没有时退化为 url，url 变化即视为新内容。
     */
    private String imageHash(Picture picture) {
        String seed = StrUtil.isNotBlank(picture.getFileHash())
                ? "file:" + picture.getFileHash()
                : "url:" + StrUtil.nullToEmpty(picture.getUrl());
        return EmbeddingText.sha256Hex(seed);
    }

    private static Integer parseIndexVersion(String indexName) {
        if (indexName == null) {
            return null;
        }
        Matcher matcher = INDEX_NAME_PATTERN.matcher(indexName);
        if (!matcher.matches()) {
            return null;
        }
        try {
            return Integer.valueOf(matcher.group(1));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String errorMessage(Throwable throwable) {
        if (throwable == null) {
            return "未知错误";
        }
        String message = throwable.getMessage();
        return StrUtil.isBlank(message) ? throwable.getClass().getSimpleName() : message;
    }

    /**
     * 重建时的一张图片计划：只保存 hash 与「有没有文本」，避免把向量一起放进列表。
     */
    private record IngestPlan(Picture picture, String textHash, String imageHash, boolean hasText) {
    }

    /**
     * 图片向量化结果：成功时 vector 非空，失败时 error 非空。
     */
    private record ImageOutcome(float[] vector, RuntimeException error) {
    }

    /**
     * 重建成功后需要回写状态的两侧 hash。
     */
    private record SuccessMark(Long pictureId, String textHash, String imageHash, boolean hasText) {
    }
}

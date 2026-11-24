package com.tzy.picturebackend.mq;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.tzy.picturebackend.model.entity.Picture;
import com.tzy.picturebackend.model.enums.PictureReviewStatusEnum;
import com.tzy.picturebackend.service.PictureSearchService;
import com.tzy.picturebackend.service.PictureService;
import com.tzy.picturebackend.service.RagEmbeddingStateService;
import com.tzy.picturebackend.service.RagIndexService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 搜索索引一致性巡检。
 * <p>
 * 每天对比 MySQL 中已通过审核的图片数与索引文档数，不一致时记录告警日志。
 * 事件丢失、消费者进死信或索引被误删都会表现为数量不一致；
 * 日志关键字为「搜索索引与数据库不一致」，可直接接监控告警（B3-1），修复方式是调用
 * {@code POST /api/search/reindex} 全量重建。
 * <p>
 * RAG 开启时追加一条向量索引巡检：向量文档数 vs {@code picture_embedding} 中文本向量成功且公开的图片数，
 * 不一致时记录「向量索引与数据库不一致」，修复方式是 {@code POST /api/rag/reindex}。
 * <p>
 * 只在 {@code app.search.engine=es} 时注册，MySQL 模式下不会执行。
 */
@Component
@ConditionalOnProperty(prefix = "app.search", name = "engine", havingValue = "es")
@Slf4j
public class PictureSearchConsistencyTask {

    private final PictureService pictureService;

    private final PictureSearchService pictureSearchService;

    private final RagEmbeddingStateService ragEmbeddingStateService;

    private final ObjectProvider<RagIndexService> ragIndexServiceProvider;

    public PictureSearchConsistencyTask(PictureService pictureService,
                                        PictureSearchService pictureSearchService,
                                        RagEmbeddingStateService ragEmbeddingStateService,
                                        ObjectProvider<RagIndexService> ragIndexServiceProvider) {
        this.pictureService = pictureService;
        this.pictureSearchService = pictureSearchService;
        this.ragEmbeddingStateService = ragEmbeddingStateService;
        this.ragIndexServiceProvider = ragIndexServiceProvider;
    }

    /**
     * 每天凌晨 3 点执行
     */
    @Scheduled(cron = "0 0 3 * * ?")
    public void checkConsistency() {
        checkSearchConsistency();
        checkRagConsistency();
    }

    private void checkSearchConsistency() {
        long mysqlCount;
        try {
            mysqlCount = pictureService.count(new QueryWrapper<Picture>()
                    .eq("reviewStatus", PictureReviewStatusEnum.PASS.getValue()));
        } catch (RuntimeException e) {
            log.error("搜索索引巡检读取数据库失败", e);
            return;
        }
        long indexCount;
        try {
            indexCount = pictureSearchService.countIndexed();
        } catch (RuntimeException e) {
            log.error("搜索索引巡检读取索引失败", e);
            return;
        }
        if (mysqlCount != indexCount) {
            log.warn("搜索索引与数据库不一致：公开图片 {} 张，索引文档 {} 条，"
                    + "可调用 POST /api/search/reindex 重建索引", mysqlCount, indexCount);
            return;
        }
        log.info("搜索索引巡检通过：公开图片 {} 张，索引文档 {} 条", mysqlCount, indexCount);
    }

    /**
     * 向量索引巡检：只在 RAG 真正可用时执行；读取失败只记录错误，不影响 BM25 巡检结果。
     */
    private void checkRagConsistency() {
        RagIndexService ragIndexService = ragIndexServiceProvider.getIfAvailable();
        if (ragIndexService == null || !ragIndexService.enabled()) {
            return;
        }
        long expected;
        long actual;
        try {
            expected = ragEmbeddingStateService.countSuccessPublic();
            actual = ragIndexService.countIndexed();
        } catch (RuntimeException e) {
            log.error("向量索引巡检失败", e);
            return;
        }
        if (expected != actual) {
            log.warn("向量索引与数据库不一致：成功向量化的公开图片 {} 张，索引文档 {} 条，"
                    + "可调用 POST /api/rag/reindex 重建索引", expected, actual);
            return;
        }
        log.info("向量索引巡检通过：成功向量化的公开图片 {} 张，索引文档 {} 条", expected, actual);
    }
}

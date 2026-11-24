package com.tzy.picturebackend.config;

import lombok.Data;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * RAG 配置：默认全关，关闭时不注册任何向量化 / 检索 Bean，也不产生外部调用。
 * <p>
 * 配置优先级沿用项目约定：内置默认 &lt; application-local.yml &lt; 环境变量。
 * API Key 只从环境变量读取，不写入 application.yml，也不出现在日志里。
 */
@Component
@ConfigurationProperties(prefix = "app.rag")
@Data
public class RagProperties {

    /**
     * 总开关：false 时只装配空实现
     */
    private boolean enabled = false;

    private Embedding embedding = new Embedding();

    private Index index = new Index();

    private Search search = new Search();

    private Ask ask = new Ask();

    /**
     * 向量化配置
     */
    @Data
    public static class Embedding {

        /**
         * 百炼 OpenAI 兼容接口地址
         */
        private String baseUrl = "https://dashscope.aliyuncs.com";

        /**
         * 百炼 API Key，生产环境通过 DASHSCOPE_API_KEY 注入
         */
        @ToString.Exclude
        private String apiKey;

        /**
         * 文本向量模型
         */
        private String textModel = "text-embedding-v4";

        /**
         * 文本向量维度
         */
        private int textDim = 1024;

        /**
         * 多模态向量模型（固定 1024 维）
         */
        private String imageModel = "multimodal-embedding-v1";

        /**
         * 图片向量维度
         */
        private int imageDim = 1024;

        /**
         * 单次请求的最大条数
         */
        private int batchSize = 10;

        /**
         * 连接与单次请求超时
         */
        private Duration timeout = Duration.ofSeconds(30);

        /**
         * 查询向量化超时：搜索请求的延迟敏感，超时后直接回落 BM25，不占用请求线程
         */
        private Duration queryTimeout = Duration.ofSeconds(3);

        /**
         * 失败后的最大重试次数（不含首次调用）
         */
        private int maxRetries = 2;

        /**
         * 重试基础间隔，单位毫秒；第 n 次重试等待 n 倍
         */
        private long retryDelayMillis = 1000;

        /**
         * 图片向量输入方式：base64（默认，兼容私密图/防盗链）| url
         */
        private String imageInput = "base64";
    }

    /**
     * 向量索引配置（M2 使用）
     */
    @Data
    public static class Index {

        private String alias = "picture_vec";

        private String name = "picture_vec_v1";

        private int dims = 1024;

        /**
         * 全量重建时图片向量化的并发度；图片接口一次只能传一张，并发能显著缩短重建时间
         */
        private int reindexThreads = 3;
    }

    /**
     * 检索融合配置（M3 使用）
     */
    @Data
    public static class Search {

        /**
         * rrf | weighted
         */
        private String fusion = "rrf";

        private int rrfK = 60;

        private int bm25Top = 50;

        private int knnTop = 50;

        private int numCandidates = 200;

        /**
         * 查询向量缓存秒数
         */
        private int queryCacheSeconds = 600;

        /**
         * 混合检索结果缓存秒数（单独 key，与公开列表缓存隔离；重建索引时清空）
         */
        private int resultCacheSeconds = 300;

        /**
         * 融合检索最多覆盖的前 N 条：分页深度超过此值说明用户已翻过融合窗口，
         * 此时回落到 BM25，避免为了深分页把 kNN 的 k 放大到不可控。
         */
        private int maxTop = 500;
    }

    /**
     * 图库问答配置（M5 使用）
     */
    @Data
    public static class Ask {

        private int dailyLimit = 10;

        private int topK = 8;

        private int maxAnswerChars = 400;

        private int cacheSeconds = 3600;

        private Duration timeout = Duration.ofSeconds(25);

        /**
         * 近重复只记指标的阈值
         */
        private double suspectThreshold = 0.97;

        /**
         * 近重复发通知的阈值
         */
        private double duplicateThreshold = 0.985;
    }
}

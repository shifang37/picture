package com.tzy.picturebackend.metrics;

import com.github.benmanes.caffeine.cache.Cache;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 业务指标的统一出口。
 * <p>
 * 指标名使用点号分隔，由 Prometheus registry 转成下划线并补 {@code _total} / {@code _seconds}：
 * <ul>
 *   <li>{@code picture.api.errors} → {@code picture_api_errors_total{code}}</li>
 *   <li>{@code picture.rate.limited} → {@code picture_rate_limited_total{type}}</li>
 *   <li>{@code picture.upload} → {@code picture_upload_total{result}}</li>
 *   <li>{@code picture.compress} → {@code picture_compress_seconds}</li>
 *   <li>{@code picture.review.pending} → {@code picture_review_pending}</li>
 *   <li>{@code picture.ai.tokens} → {@code picture_ai_tokens_total{direction}}</li>
 *   <li>{@code picture.search.result} → {@code picture_search_result_total{hit,mode}}</li>
 *   <li>{@code picture.rag.embed} → {@code picture_rag_embed_total{kind,result}} / {@code picture_rag_embed_seconds{kind}}</li>
 *   <li>{@code picture.rag.index} → {@code picture_rag_index_total{action,result}}</li>
 *   <li>{@code picture.rag.search} → {@code picture_rag_search_seconds{mode}}</li>
 *   <li>{@code picture.rag.degraded} → {@code picture_rag_degraded_total{reason}}</li>
 *   <li>{@code picture.rag.duplicate} → {@code picture_rag_duplicate_total{result}}</li>
 * </ul>
 * <p>
 * 为什么不统计 HTTP 5xx：{@code GlobalExceptionHandler} 与限流响应都返回 HTTP 200 + 业务码，
 * Spring 自带的 {@code http_server_requests} 几乎全是 200，告警只能按业务码计数。
 */
@Component
public class PictureMetrics {

    /**
     * 接口业务错误码计数器（按业务码打标签）
     */
    public static final String API_ERRORS = "picture.api.errors";

    /**
     * 限流计数器（type=login/register/captcha/upload）
     */
    public static final String RATE_LIMITED = "picture.rate.limited";

    /**
     * 上传结果计数器（result=success/failure）
     */
    public static final String UPLOAD = "picture.upload";

    /**
     * 原图压缩计时器
     */
    public static final String COMPRESS = "picture.compress";

    /**
     * 待审核图片数量
     */
    public static final String REVIEW_PENDING = "picture.review.pending";

    /**
     * AI 调用 token 计数（direction=prompt/completion）
     */
    public static final String AI_TOKENS = "picture.ai.tokens";

    /**
     * 收藏/点赞计数器（action=add/remove，type=favorite/like）
     */
    public static final String INTERACTION = "picture.interaction";

    /**
     * 站内通知发送计数器（type=REVIEW_PASS/REVIEW_REJECT/...）
     */
    public static final String NOTIFICATION_SENT = "notification.sent";

    /**
     * 站内通知已读计数器
     */
    public static final String NOTIFICATION_READ = "notification.read";

    /**
     * 批量编辑 / 批量删除耗时（operation=edit/delete）
     */
    public static final String BATCH = "picture.batch";

    /**
     * 搜索元数据请求计数（type=suggest/hot）
     */
    public static final String SEARCH_META = "picture.search.meta";

    /**
     * 公开搜索召回结果计数（hit=0/1，mode=mysql/es）
     */
    public static final String SEARCH_RESULT = "picture.search.result";

    /**
     * RAG 向量化调用计数与耗时（kind=text/image，result=ok/fail）
     */
    public static final String RAG_EMBED = "picture.rag.embed";

    /**
     * 向量索引维护计数（action=write/delete/reindex/guard，result=ok/skip/fail/stale）
     */
    public static final String RAG_INDEX = "picture.rag.index";

    /**
     * 混合 / 语义检索耗时（mode=hybrid/semantic）
     */
    public static final String RAG_SEARCH = "picture.rag.search";

    /**
     * RAG 降级计数（reason=embed_timeout/embed_error/es_error/disabled/...）
     */
    public static final String RAG_DEGRADED = "picture.rag.degraded";

    /**
     * 近重复检测计数（result=hit/suspect/miss/error）
     */
    public static final String RAG_DUPLICATE = "picture.rag.duplicate";

    /**
     * 分享卡片渲染计数（result=rendered/not_found）
     */
    public static final String SHARE = "picture.share";

    /**
     * EXIF 处理计数（action=rotated/stripped）
     */
    public static final String EXIF = "picture.exif";

    /**
     * 配额拒绝计数（type=storage/count）
     */
    public static final String QUOTA_REJECTED = "picture.quota.rejected";

    /**
     * 秒传命中计数
     */
    public static final String UPLOAD_DUPLICATE = "picture.upload.duplicate";

    private final MeterRegistry registry;

    /**
     * 待审核数量由定时任务刷新，Gauge 只读取这个值，避免抓取时打数据库
     */
    private final AtomicLong reviewPending = new AtomicLong();

    public PictureMetrics(MeterRegistry registry) {
        this.registry = registry;
        Gauge.builder(REVIEW_PENDING, reviewPending, AtomicLong::doubleValue)
                .description("待审核图片数量")
                .register(registry);
    }

    /**
     * 记录一次接口业务错误。HTTP 状态码始终是 200，业务码才是真正的错误信号。
     */
    public void apiError(int code) {
        Counter.builder(API_ERRORS)
                .description("接口业务错误次数")
                .tag("code", String.valueOf(code))
                .register(registry)
                .increment();
    }

    /**
     * 记录一次被限流的请求
     *
     * @param type login / register / captcha / upload
     */
    public void rateLimited(String type) {
        Counter.builder(RATE_LIMITED)
                .description("被限流的请求次数")
                .tag("type", type)
                .register(registry)
                .increment();
    }

    /**
     * 记录一次上传结果
     *
     * @param result success / failure
     */
    public void upload(String result) {
        Counter.builder(UPLOAD)
                .description("图片上传次数")
                .tag("result", result)
                .register(registry)
                .increment();
    }

    /**
     * 原图压缩计时器；调用方用 {@code record(...)} 包住压缩逻辑
     */
    public Timer compressTimer() {
        return Timer.builder(COMPRESS)
                .description("原图压缩耗时")
                // 显式开启直方图桶，Grafana 的 p95 不依赖额外的 metrics 配置
                .publishPercentileHistogram()
                .register(registry);
    }

    /**
     * 更新待审核图片数量
     */
    public void setReviewPending(long count) {
        reviewPending.set(Math.max(0, count));
    }

    /**
     * 记录 AI 调用消耗的 token
     *
     * @param direction prompt / completion
     * @param tokens    本次消耗的 token 数
     */
    public void aiTokens(String direction, long tokens) {
        if (tokens <= 0) {
            return;
        }
        Counter.builder(AI_TOKENS)
                .description("AI 调用消耗的 token 数")
                .tag("direction", direction)
                .register(registry)
                .increment(tokens);
    }

    /**
     * 记录一次收藏/点赞操作
     *
     * @param action add / remove
     * @param type   favorite / like
     */
    public void interaction(String action, String type) {
        Counter.builder(INTERACTION)
                .description("图片收藏/点赞次数")
                .tag("action", action)
                .tag("type", type)
                .register(registry)
                .increment();
    }

    /**
     * 记录一次站内通知发送（按通知类型区分，便于发现审核拒绝量突增）
     */
    public void notificationSent(String type) {
        Counter.builder(NOTIFICATION_SENT)
                .description("站内通知发送次数")
                .tag("type", type == null ? "UNKNOWN" : type)
                .register(registry)
                .increment();
    }

    /**
     * 记录已读通知数量（通知触达效果的粗略指标）
     */
    public void notificationRead(int count) {
        if (count <= 0) {
            return;
        }
        Counter.builder(NOTIFICATION_READ)
                .description("站内通知已读次数")
                .register(registry)
                .increment(count);
    }

    /**
     * 记录一次批量操作的耗时
     *
     * @param operation edit / delete
     * @param nanos     耗时（纳秒）
     */
    public void batch(String operation, long nanos) {
        if (nanos < 0) {
            return;
        }
        Timer.builder(BATCH)
                .description("图片批量操作耗时")
                .tag("operation", operation == null ? "unknown" : operation)
                .publishPercentileHistogram()
                .register(registry)
                .record(nanos, TimeUnit.NANOSECONDS);
    }

    /**
     * 记录一次搜索元数据请求（联想 / 热词）
     *
     * @param type suggest / hot
     */
    public void searchMeta(String type) {
        Counter.builder(SEARCH_META)
                .description("搜索联想与热词请求次数")
                .tag("type", type == null ? "unknown" : type)
                .register(registry)
                .increment();
    }

    /**
     * 记录一次公开搜索的召回结果，用于计算零结果率基线。
     * <p>
     * 只统计带 {@code searchText} 且未命中列表缓存的请求：缓存命中不是一次新的检索，
     * 计入会让「同一查询反复刷新」稀释零结果率。{@code hit} 按总命中数判断而不是当前页条数，
     * 避免翻到超出末页时被误记成零结果。
     *
     * @param hit  是否召回到至少一张图片
     * @param mode 实际提供数据的路径：mysql / es（M3 起为 hybrid / semantic）
     */
    public void searchResult(boolean hit, String mode) {
        Counter.builder(SEARCH_RESULT)
                .description("公开搜索召回结果次数")
                .tag("hit", hit ? "1" : "0")
                .tag("mode", mode == null ? "unknown" : mode)
                .register(registry)
                .increment();
    }

    /**
     * 记录一次向量化调用及其耗时。
     * <p>
     * 每次尝试都记一次：重试前的失败也是一次真实的调用失败，便于按 kind 观察重试与降级。
     *
     * @param kind    text / image
     * @param success 本次尝试是否成功
     * @param nanos   本次尝试耗时（纳秒）
     */
    public void ragEmbed(String kind, boolean success, long nanos) {
        String normalizedKind = kind == null ? "unknown" : kind;
        Counter.builder(RAG_EMBED)
                .description("RAG 向量化调用次数")
                .tag("kind", normalizedKind)
                .tag("result", success ? "ok" : "fail")
                .register(registry)
                .increment();
        if (nanos >= 0) {
            Timer.builder(RAG_EMBED)
                    .description("RAG 向量化耗时")
                    .tag("kind", normalizedKind)
                    .publishPercentileHistogram()
                    .register(registry)
                    .record(nanos, TimeUnit.NANOSECONDS);
        }
    }

    /**
     * 记录一次向量索引维护动作。
     *
     * @param action write / delete / reindex / guard
     * @param result ok / skip / fail / stale
     */
    public void ragIndex(String action, String result) {
        Counter.builder(RAG_INDEX)
                .description("RAG 向量索引维护次数")
                .tag("action", action == null ? "unknown" : action)
                .tag("result", result == null ? "unknown" : result)
                .register(registry)
                .increment();
    }

    /**
     * 记录一次混合 / 语义检索的耗时。
     *
     * @param mode   请求的检索模式：hybrid / semantic
     * @param nanos  耗时（纳秒）
     */
    public void ragSearch(String mode, long nanos) {
        if (nanos < 0) {
            return;
        }
        Timer.builder(RAG_SEARCH)
                .description("RAG 混合/语义检索耗时")
                .tag("mode", mode == null ? "unknown" : mode)
                .publishPercentileHistogram()
                .register(registry)
                .record(nanos, TimeUnit.NANOSECONDS);
    }

    /**
     * 记录一次 RAG 降级：结果实际来自 BM25，提示调用方与值班同学。
     *
     * @param reason embed_timeout / embed_error / es_error / disabled / unsupported_filter / deep_page
     */
    public void ragDegraded(String reason) {
        Counter.builder(RAG_DEGRADED)
                .description("RAG 检索降级次数")
                .tag("reason", reason == null ? "unknown" : reason)
                .register(registry)
                .increment();
    }

    /**
     * 记录一次近重复检测结果。
     * <p>
     * {@code hit} = 超过判重阈值并已通知作者；{@code suspect} = 只超过疑似阈值、不打扰用户；
     * {@code miss} = 未命中；{@code error} = 向量查询失败（不影响图片本身）。
     *
     * @param result hit / suspect / miss / error
     */
    public void ragDuplicate(String result) {
        Counter.builder(RAG_DUPLICATE)
                .description("RAG 近重复检测次数")
                .tag("result", result == null ? "unknown" : result)
                .register(registry)
                .increment();
    }

    /**
     * 记录一次分享卡片渲染：{@code not_found} 突增通常意味着有人批量探测图片 id
     *
     * @param result rendered / not_found
     */
    public void share(String result) {
        Counter.builder(SHARE)
                .description("图片分享卡片渲染次数")
                .tag("result", result == null ? "unknown" : result)
                .register(registry)
                .increment();
    }

    /**
     * 记录一次 EXIF 处理
     *
     * @param action rotated（方向纠正）/ stripped（剥离 GPS 等元数据）
     */
    public void exif(String action) {
        Counter.builder(EXIF)
                .description("上传图片 EXIF 处理次数")
                .tag("action", action == null ? "unknown" : action)
                .register(registry)
                .increment();
    }

    /**
     * 记录一次配额拒绝
     *
     * @param type storage（存储不足）/ count（数量到达上限）
     */
    public void quotaRejected(String type) {
        Counter.builder(QUOTA_REJECTED)
                .description("因配额不足被拒绝的上传次数")
                .tag("type", type == null ? "unknown" : type)
                .register(registry)
                .increment();
    }

    /**
     * 记录一次秒传命中（同一用户上传了已存在的文件）
     */
    public void uploadDuplicate() {
        Counter.builder(UPLOAD_DUPLICATE)
                .description("同用户相同文件秒传命中次数")
                .register(registry)
                .increment();
    }

    /**
     * 把 Caffeine 本地缓存的命中率接入 Micrometer（cache_gets_total 等）。
     * <p>
     * 必须显式补上 {@code cache.manager} 与 {@code name} 两个标签：Spring Boot 为
     * RedisCacheManager 注册的 {@code cache.gets} 带有这两个标签，而 Caffeine 默认只带
     * {@code cache}。同一个 Prometheus registry 中同名指标的标签键集合必须一致，
     * 否则其中一方会注册失败，表现为缓存命中率指标缺失并打印 registration failed 警告。
     */
    public void registerCaffeineCache(String cacheName, Cache<?, ?> cache) {
        CaffeineCacheMetrics.monitor(registry, cache, cacheName,
                Tags.of("cache.manager", "caffeine", "name", cacheName));
    }
}

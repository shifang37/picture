package com.tzy.picturebackend.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.tzy.picturebackend.config.RagProperties;
import com.tzy.picturebackend.exception.BusinessException;
import com.tzy.picturebackend.exception.ErrorCode;
import com.tzy.picturebackend.exception.ThrowUtils;
import com.tzy.picturebackend.manager.RagAskCache;
import com.tzy.picturebackend.manager.rag.RagAnswerException;
import com.tzy.picturebackend.manager.rag.RagAnswerGenerator;
import com.tzy.picturebackend.manager.rag.RagAnswerPayload;
import com.tzy.picturebackend.manager.rag.RagAskPromptBuilder;
import com.tzy.picturebackend.metrics.PictureMetrics;
import com.tzy.picturebackend.model.dto.rag.RagAskRequest;
import com.tzy.picturebackend.model.entity.Picture;
import com.tzy.picturebackend.model.enums.PictureReviewStatusEnum;
import com.tzy.picturebackend.model.vo.rag.RagAskResponse;
import com.tzy.picturebackend.model.vo.rag.RagCitationVO;
import com.tzy.picturebackend.service.PictureService;
import com.tzy.picturebackend.service.RagAskService;
import com.tzy.picturebackend.service.RagIndexService;
import com.tzy.picturebackend.service.RagSearchService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 图库问答实现。
 * <p>
 * 流程：每日限额 → 回答缓存 → 问题向量化 + kNN 召回 → MySQL 回表二次过滤
 * → 无候选直接拒答 → 组 prompt → DeepSeek → 解析 + 引用硬校验 → 返回。
 * <p>
 * 安全边界：
 * <ul>
 *   <li>模型只看到已过审图片的公开字段，未过审 / 私密内容不会进入提示词；</li>
 *   <li>模型返回的 id 不在候选集、回答超长、拒答却带引用，一律整条拒答，
 *       不返回任何模型生成的文本，避免部分泄露；</li>
 *   <li>缓存只存「回答 + 引用 id」，命中后重新回表构造引用，图片下架即视为未命中。</li>
 * </ul>
 */
@Slf4j
public class RagAskServiceImpl implements RagAskService {

    /**
     * 每用户每日问答次数的 Redis key 前缀
     */
    static final String DAILY_LIMIT_KEY_PREFIX = "picture:rag:ask:limit:";

    /**
     * 计数 key 保留两天，跨天自然失效
     */
    static final Duration DAILY_LIMIT_TTL = Duration.ofDays(2);

    /**
     * 业务日期按北京时间计算，与 AI 建议的限额口径一致
     */
    private static final ZoneId ASK_ZONE = ZoneId.of("Asia/Shanghai");

    private static final DateTimeFormatter DAY_FORMATTER = DateTimeFormatter.BASIC_ISO_DATE;

    /**
     * 问题进入提示词前的硬上限，与请求 DTO 的 @Size 保持一致
     */
    static final int QUESTION_MAX_CHARS = 200;

    /**
     * topK 的安全上限，避免直调 service 时把 kNN 的 k 放大到不可控
     */
    static final int TOP_K_MAX = 20;

    private final RagSearchService ragSearchService;

    private final PictureService pictureService;

    private final RagIndexService ragIndexService;

    private final RagAnswerGenerator ragAnswerGenerator;

    private final RagAskPromptBuilder ragAskPromptBuilder;

    private final RagAskCache ragAskCache;

    private final StringRedisTemplate redisTemplate;

    private final RagProperties ragProperties;

    private final PictureMetrics pictureMetrics;

    public RagAskServiceImpl(RagSearchService ragSearchService,
                             PictureService pictureService,
                             RagIndexService ragIndexService,
                             RagAnswerGenerator ragAnswerGenerator,
                             RagAskPromptBuilder ragAskPromptBuilder,
                             RagAskCache ragAskCache,
                             StringRedisTemplate redisTemplate,
                             RagProperties ragProperties,
                             PictureMetrics pictureMetrics) {
        this.ragSearchService = ragSearchService;
        this.pictureService = pictureService;
        this.ragIndexService = ragIndexService;
        this.ragAnswerGenerator = ragAnswerGenerator;
        this.ragAskPromptBuilder = ragAskPromptBuilder;
        this.ragAskCache = ragAskCache;
        this.redisTemplate = redisTemplate;
        this.ragProperties = ragProperties;
        this.pictureMetrics = pictureMetrics;
    }

    @Override
    public boolean enabled() {
        return ragSearchService.enabled();
    }

    @Override
    public RagAskResponse ask(RagAskRequest request, Long userId) {
        long started = System.nanoTime();
        String result = "error";
        try {
            if (!enabled()) {
                pictureMetrics.ragDegraded("disabled");
                return RagAskResponse.degraded();
            }
            String question = normalizeQuestion(request == null ? null : request.getQuestion());
            ThrowUtils.throwIf(question.isEmpty(), ErrorCode.PARAMS_ERROR, "问题不能为空");
            ThrowUtils.throwIf(question.length() > QUESTION_MAX_CHARS,
                    ErrorCode.PARAMS_ERROR, "问题不能超过 " + QUESTION_MAX_CHARS + " 个字符");
            int topK = resolveTopK(request == null ? null : request.getTopK());

            // 1. 每日限额：放在缓存之前，缓存命中同样算一次提问（防止靠重复问绕开成本控制）
            Boolean allowed = tryAcquireDailyQuota(userId);
            if (allowed == null) {
                pictureMetrics.ragDegraded("ask_error");
                return RagAskResponse.degraded();
            }
            if (!allowed) {
                result = "limited";
                throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS,
                        "今日问答次数已用完（每天 " + dailyLimit() + " 次），请明天再试");
            }

            // 2. 回答缓存：key 含索引名、提示词版本与模型，重建/升级后自然失效
            String indexVersion = indexVersion();
            String model = ragAnswerGenerator.model();
            RagAskCache.CachedAnswer cached = ragAskCache.get(question, topK, indexVersion,
                    RagAskPromptBuilder.PROMPT_VERSION, model);
            if (cached != null) {
                RagAskResponse fromCache = fromCache(cached);
                if (fromCache != null) {
                    result = cached.refused() ? "refused" : "answered";
                    return fromCache;
                }
            }

            // 3. 召回：向量索引只负责给候选，事实与权限仍以 MySQL 为准
            List<Long> recalledIds = ragSearchService.askCandidates(question, topK);
            List<Picture> candidates = loadCandidates(recalledIds, topK);
            if (candidates.isEmpty()) {
                result = "refused";
                RagAskResponse refused = RagAskResponse.refused();
                cacheRefusal(question, topK, indexVersion, model);
                return refused;
            }

            // 4. 生成：无候选时不调用模型（上面已提前返回），这里只处理有候选的情况
            RagAnswerPayload payload;
            try {
                payload = ragAnswerGenerator.generate(
                        ragAskPromptBuilder.systemPrompt(),
                        ragAskPromptBuilder.userPrompt(question, candidates));
            } catch (RagAnswerException e) {
                pictureMetrics.ragDegraded(e.isTimeout() ? "ask_timeout" : "ask_error");
                log.warn("图库问答生成失败，降级返回固定文案, question={}, retryable={}, message={}",
                        question, e.isRetryable(), e.getMessage());
                return RagAskResponse.degraded();
            }

            // 5. 引用硬校验：任何一条不满足都整条拒答，不返回部分模型文本
            Validation validation = validate(payload, candidates);
            if (!validation.valid()) {
                result = "invalid_citation";
                log.warn("图库问答引用校验失败，按拒答处理, question={}, answerLength={}, cited={}",
                        question, payload == null ? null : StrUtil.length(payload.answer()),
                        payload == null ? null : payload.citedPictureIds());
                return RagAskResponse.refused();
            }
            if (validation.refused()) {
                result = "refused";
                RagAskResponse refused = RagAskResponse.refused();
                cacheRefusal(question, topK, indexVersion, model);
                return refused;
            }

            result = "answered";
            RagAskResponse response = RagAskResponse.answered(validation.answer(), validation.citations());
            ragAskCache.put(question, topK, indexVersion, RagAskPromptBuilder.PROMPT_VERSION, model,
                    new RagAskCache.CachedAnswer(response.getAnswer(), validation.citedPictureIds(), false));
            return response;
        } catch (BusinessException e) {
            // 参数错误与超限要保留业务码，不能降级成「暂时不可用」
            throw e;
        } catch (RuntimeException e) {
            // 缓存、回表或其它未预期异常：问答是增强功能，不能把主流程拖成 5xx
            log.error("图库问答执行异常，降级返回固定文案", e);
            pictureMetrics.ragDegraded("ask_error");
            result = "error";
            return RagAskResponse.degraded();
        } finally {
            pictureMetrics.ragAsk(result, System.nanoTime() - started);
        }
    }

    /**
     * 缓存命中后重新回表：任何一张引用图不再公开，就当作未命中重新生成，
     * 避免缓存把已删除 / 已下架的图片继续展示出来。
     */
    private RagAskResponse fromCache(RagAskCache.CachedAnswer cached) {
        if (cached.refused()) {
            return RagAskResponse.refused(StrUtil.blankToDefault(cached.answer(),
                    RagAskResponse.ANSWER_NOT_FOUND));
        }
        List<Long> ids = cached.citedPictureIds();
        if (ids == null || ids.isEmpty()) {
            return null;
        }
        List<RagCitationVO> citations;
        try {
            citations = buildCitations(ids);
        } catch (RuntimeException e) {
            log.warn("问答缓存回表失败，改走重新生成", e);
            return null;
        }
        if (citations.size() != ids.size()) {
            // 引用图缺失或已下架：缓存不可信，重新召回
            return null;
        }
        return RagAskResponse.answered(cached.answer(), citations);
    }

    private void cacheRefusal(String question, int topK, String indexVersion, String model) {
        ragAskCache.put(question, topK, indexVersion, RagAskPromptBuilder.PROMPT_VERSION, model,
                new RagAskCache.CachedAnswer(RagAskResponse.ANSWER_NOT_FOUND, List.of(), true));
    }

    /**
     * 按召回顺序回表并二次过滤 {@code reviewStatus=1}；重复 id 只保留一次
     */
    private List<Picture> loadCandidates(List<Long> recalledIds, int topK) {
        if (recalledIds == null || recalledIds.isEmpty()) {
            return List.of();
        }
        List<Long> ids = recalledIds.stream()
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            return List.of();
        }
        List<Picture> found = pictureService.listByIds(ids);
        Map<Long, Picture> pictureMap = (found == null ? List.<Picture>of() : found).stream()
                .filter(item -> item.getId() != null)
                .collect(Collectors.toMap(Picture::getId, Function.identity(), (left, right) -> left));
        // 先过滤再截断：万一索引里有脏文档（未过审），不能让它挤掉后面真正公开的候选
        int limit = Math.max(1, topK);
        List<Picture> candidates = new ArrayList<>(Math.min(ids.size(), limit));
        for (Long id : ids) {
            Picture picture = pictureMap.get(id);
            if (picture != null && Objects.equals(picture.getReviewStatus(),
                    PictureReviewStatusEnum.PASS.getValue())) {
                candidates.add(picture);
                if (candidates.size() >= limit) {
                    break;
                }
            }
        }
        return candidates;
    }

    private List<RagCitationVO> buildCitations(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<Long> unique = ids.stream().filter(Objects::nonNull).distinct().toList();
        List<Picture> found = pictureService.listByIds(unique);
        Map<Long, Picture> pictureMap = (found == null ? List.<Picture>of() : found).stream()
                .filter(item -> item.getId() != null)
                .collect(Collectors.toMap(Picture::getId, Function.identity(), (left, right) -> left));
        List<RagCitationVO> citations = new ArrayList<>(unique.size());
        for (Long id : unique) {
            Picture picture = pictureMap.get(id);
            if (picture == null || !Objects.equals(picture.getReviewStatus(),
                    PictureReviewStatusEnum.PASS.getValue())) {
                continue;
            }
            citations.add(toCitation(picture));
        }
        return citations;
    }

    private RagCitationVO toCitation(Picture picture) {
        RagCitationVO citation = new RagCitationVO();
        citation.setId(picture.getId());
        citation.setName(picture.getName());
        citation.setPictureUrl(picture.getUrl());
        citation.setThumbnailUrl(thumbnailUrl(picture.getUrl()));
        citation.setReason(reasonOf(picture));
        return citation;
    }

    /**
     * 引用理由由代码拼接事实字段，不采用模型生成内容，避免「理由」成为新的幻觉来源
     */
    private String reasonOf(Picture picture) {
        List<String> parts = new ArrayList<>();
        if (StrUtil.isNotBlank(picture.getCategory())) {
            parts.add("分类：" + StrUtil.trim(picture.getCategory()));
        }
        List<String> tags = parseTags(picture.getTags());
        if (!tags.isEmpty()) {
            parts.add("标签：" + String.join("、", tags));
        }
        if (picture.getPicWidth() != null && picture.getPicHeight() != null
                && picture.getPicWidth() > 0 && picture.getPicHeight() > 0) {
            parts.add("尺寸：" + picture.getPicWidth() + "x" + picture.getPicHeight()
                    + "（" + orientation(picture) + "）");
        }
        return parts.isEmpty() ? "命中语义检索" : String.join("；", parts);
    }

    private static String orientation(Picture picture) {
        int width = picture.getPicWidth();
        int height = picture.getPicHeight();
        if (width < height) {
            return "竖版";
        }
        if (width > height) {
            return "横版";
        }
        return "方图";
    }

    private static List<String> parseTags(String tagsJson) {
        if (StrUtil.isBlank(tagsJson)) {
            return List.of();
        }
        try {
            List<String> tags = JSONUtil.toList(tagsJson, String.class);
            if (tags == null) {
                return List.of();
            }
            return tags.stream().map(StrUtil::trim).filter(StrUtil::isNotBlank).toList();
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    static String thumbnailUrl(String url) {
        if (StrUtil.isBlank(url) || url.contains("imageMogr2")) {
            return url;
        }
        return url + (url.contains("?") ? "&" : "?") + "imageMogr2/thumbnail/400x";
    }

    /**
     * 校验模型输出；任何一条不满足都返回 invalid，调用方统一按拒答处理
     */
    private Validation validate(RagAnswerPayload payload, List<Picture> candidates) {
        if (payload == null) {
            return Validation.invalidCase();
        }
        String answer = StrUtil.trim(payload.answer());
        if (StrUtil.isBlank(answer)) {
            return Validation.invalidCase();
        }
        List<Long> citedIds = sanitizeCitedIds(payload.citedPictureIds());
        if (citedIds == null) {
            return Validation.invalidCase();
        }
        if (payload.refused()) {
            // 拒答必须没有引用，且不返回模型文本（可能夹带候选之外的信息）
            return citedIds.isEmpty() ? Validation.refusal() : Validation.invalidCase();
        }
        if (citedIds.isEmpty()) {
            // 产品要求「回答必须带引用」，没有引用的回答一律视为无效
            return Validation.invalidCase();
        }
        if (citedIds.size() > RagAskPromptBuilder.MAX_CITED_PICTURES) {
            return Validation.invalidCase();
        }
        int maxAnswerChars = Math.max(1, ragProperties.getAsk().getMaxAnswerChars());
        if (answer.length() > maxAnswerChars) {
            return Validation.invalidCase();
        }
        Set<Long> candidateIds = candidates.stream()
                .map(Picture::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (!candidateIds.containsAll(citedIds)) {
            return Validation.invalidCase();
        }
        List<RagCitationVO> citations = buildCitations(citedIds);
        if (citations.size() != citedIds.size()) {
            return Validation.invalidCase();
        }
        return Validation.answeredCase(answer, citedIds, citations);
    }

    /**
     * 去重、保序；出现 null 或非正 id 直接判为非法
     */
    private static List<Long> sanitizeCitedIds(List<Long> rawIds) {
        if (rawIds == null) {
            return List.of();
        }
        Set<Long> unique = new LinkedHashSet<>();
        for (Long id : rawIds) {
            if (id == null || id <= 0) {
                return null;
            }
            unique.add(id);
        }
        return List.copyOf(unique);
    }

    private int resolveTopK(Integer requested) {
        int configured = ragProperties.getAsk().getTopK();
        int value = requested == null ? configured : requested;
        return Math.max(1, Math.min(TOP_K_MAX, value));
    }

    /**
     * 每日限额；返回 null 表示 Redis 故障（调用方降级，不放过无上限调用）。
     */
    private Boolean tryAcquireDailyQuota(Long userId) {
        int limit = dailyLimit();
        if (userId == null || limit <= 0) {
            return false;
        }
        String key = DAILY_LIMIT_KEY_PREFIX + userId + ":"
                + LocalDate.now(ASK_ZONE).format(DAY_FORMATTER);
        try {
            Long count = redisTemplate.opsForValue().increment(key);
            if (count != null && count == 1L) {
                redisTemplate.expire(key, DAILY_LIMIT_TTL);
            }
            return count != null && count <= limit;
        } catch (RuntimeException e) {
            log.error("图库问答每日计数失败，降级返回, userId={}", userId, e);
            return null;
        }
    }

    private int dailyLimit() {
        return ragProperties.getAsk().getDailyLimit();
    }

    /**
     * 缓存版本用当前别名指向的索引名：重建切别名后 key 自动变化。
     * 读取失败时退回配置名，不影响本次问答。
     */
    private String indexVersion() {
        try {
            return ragIndexService.indexName();
        } catch (RuntimeException e) {
            log.warn("读取向量索引名失败，问答缓存按配置名降级", e);
            return ragProperties.getIndex().getName();
        }
    }

    /**
     * 归一化问题：去首尾空白、压缩连续空白、去掉控制字符；同一句话命中同一个缓存 key
     */
    static String normalizeQuestion(String question) {
        if (question == null) {
            return "";
        }
        return question
                .replaceAll("[\\p{Cntrl}]", " ")
                .trim()
                .replaceAll("\\s+", " ");
    }

    /**
     * 校验结果：invalid / refused / answered 三态
     */
    private record Validation(boolean valid, boolean refused, String answer,
                              List<Long> citedPictureIds, List<RagCitationVO> citations) {

        static Validation invalidCase() {
            return new Validation(false, false, null, List.of(), List.of());
        }

        static Validation refusal() {
            return new Validation(true, true, RagAskResponse.ANSWER_NOT_FOUND,
                    List.of(), List.of());
        }

        static Validation answeredCase(String answer, List<Long> citedIds,
                                   List<RagCitationVO> citations) {
            return new Validation(true, false, answer, List.copyOf(citedIds),
                    List.copyOf(citations));
        }
    }
}

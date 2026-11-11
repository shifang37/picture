package com.tzy.picturebackend.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.tzy.picturebackend.config.AiProperties;
import com.tzy.picturebackend.constant.UserConstant;
import com.tzy.picturebackend.exception.ErrorCode;
import com.tzy.picturebackend.exception.ThrowUtils;
import com.tzy.picturebackend.manager.AiSuggestionException;
import com.tzy.picturebackend.manager.AiSuggestionPayload;
import com.tzy.picturebackend.manager.PictureAiTagger;
import com.tzy.picturebackend.mapper.PictureAiSuggestionMapper;
import com.tzy.picturebackend.model.entity.Picture;
import com.tzy.picturebackend.model.entity.PictureAiSuggestion;
import com.tzy.picturebackend.model.entity.User;
import com.tzy.picturebackend.model.enums.AiSuggestionStatusEnum;
import com.tzy.picturebackend.model.enums.PictureReviewStatusEnum;
import com.tzy.picturebackend.model.vo.PictureAiSuggestionVO;
import com.tzy.picturebackend.model.vo.PictureTagCategory;
import com.tzy.picturebackend.service.AiSuggestionService;
import com.tzy.picturebackend.service.PictureService;
import com.tzy.picturebackend.service.TagCategoryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * AI 建议生成与查询。
 * <p>
 * 生成流程：幂等/过期判断 -> 每日上限 -> 插入处理中记录 -> 调用模型（失败按策略重试）
 * -> 校验模型输出 -> 写入成功结果。模型输出一律视为不可信输入。
 */
@Service
@Slf4j
public class AiSuggestionServiceImpl implements AiSuggestionService {

    /**
     * 每用户每日调用次数的 Redis key 前缀
     */
    static final String DAILY_LIMIT_KEY_PREFIX = "picture:ai:limit:";

    /**
     * 计数 key 保留两天，跨天自然失效
     */
    static final Duration DAILY_LIMIT_TTL = Duration.ofDays(2);

    /**
     * 处理中记录超过该时长视为上一次执行中断，允许重新生成
     */
    static final Duration PROCESSING_TIMEOUT = Duration.ofMinutes(10);

    /**
     * 业务日期按北京时间计算
     */
    private static final ZoneId AI_ZONE = ZoneId.of("Asia/Shanghai");

    private static final DateTimeFormatter DAY_FORMATTER = DateTimeFormatter.BASIC_ISO_DATE;

    private static final int NAME_MAX_LENGTH = 128;

    private static final int INTRODUCTION_MAX_LENGTH = 512;

    private static final int CATEGORY_MAX_LENGTH = 64;

    private static final int TAG_MAX_LENGTH = 32;

    private static final int TAG_MAX_COUNT = 20;

    private static final int MAX_RETRY_AFTER_MILLIS = 5000;

    private final PictureAiSuggestionMapper pictureAiSuggestionMapper;

    private final PictureAiTagger pictureAiTagger;

    private final AiProperties aiProperties;

    private final TagCategoryService tagCategoryService;

    private final PictureService pictureService;

    private final StringRedisTemplate stringRedisTemplate;

    public AiSuggestionServiceImpl(PictureAiSuggestionMapper pictureAiSuggestionMapper,
                                   PictureAiTagger pictureAiTagger,
                                   AiProperties aiProperties,
                                   TagCategoryService tagCategoryService,
                                   PictureService pictureService,
                                   StringRedisTemplate stringRedisTemplate) {
        this.pictureAiSuggestionMapper = pictureAiSuggestionMapper;
        this.pictureAiTagger = pictureAiTagger;
        this.aiProperties = aiProperties;
        this.tagCategoryService = tagCategoryService;
        this.pictureService = pictureService;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public void generate(Picture picture) {
        if (!aiProperties.isEnabled() || picture == null || picture.getId() == null) {
            return;
        }
        if (Objects.equals(picture.getReviewStatus(), PictureReviewStatusEnum.REJECT.getValue())) {
            log.info("图片已拒绝，跳过 AI 建议, pictureId={}", picture.getId());
            return;
        }
        PictureAiSuggestion existing = findByPictureId(picture.getId());
        if (existing != null) {
            if (!isStale(existing, picture)) {
                log.info("AI 建议已存在或正在处理，跳过, pictureId={}, status={}",
                        picture.getId(), existing.getStatus());
                return;
            }
            // 图片被重传，或上一次执行中断：删除旧记录后重新生成
            log.info("删除过期 AI 建议后重新生成, pictureId={}, oldStatus={}",
                    picture.getId(), existing.getStatus());
            try {
                pictureAiSuggestionMapper.deleteById(existing.getId());
            } catch (RuntimeException e) {
                log.warn("删除过期 AI 建议失败，跳过本轮, pictureId={}", picture.getId(), e);
                return;
            }
        }
        if (!tryAcquireDailyQuota(picture.getUserId())) {
            log.info("AI 建议每日调用次数已用完, userId={}, pictureId={}",
                    picture.getUserId(), picture.getId());
            insertFailedRow(picture.getId());
            return;
        }
        PictureAiSuggestion processing = newRow(picture.getId(), AiSuggestionStatusEnum.PROCESSING);
        try {
            int inserted = pictureAiSuggestionMapper.insert(processing);
            if (inserted <= 0) {
                log.warn("AI 建议处理中记录写入失败, pictureId={}", picture.getId());
                return;
            }
        } catch (DuplicateKeyException e) {
            // 并发消费者已经插入处理中记录
            log.info("AI 建议记录已被其他消费者创建, pictureId={}", picture.getId());
            return;
        }
        try {
            PictureTagCategory dictionary = tagCategoryService.listAll();
            List<String> categoryDict = dictionary == null || dictionary.getCategoryList() == null
                    ? List.of() : dictionary.getCategoryList();
            List<String> tagDict = dictionary == null || dictionary.getTagList() == null
                    ? List.of() : dictionary.getTagList();
            AiSuggestionPayload raw = callWithRetry(buildThumbnailUrl(picture.getUrl()),
                    categoryDict, tagDict);
            AiSuggestionPayload sanitized = sanitize(raw, categoryDict, picture);
            markSuccess(processing.getId(), sanitized);
        } catch (AiSuggestionException e) {
            log.warn("AI 建议生成失败, pictureId={}, retryable={}, message={}",
                    picture.getId(), e.isRetryable(), e.getMessage());
            markFailed(processing.getId());
        } catch (RuntimeException e) {
            log.error("AI 建议生成异常, pictureId={}", picture.getId(), e);
            markFailed(processing.getId());
        }
    }

    @Override
    public PictureAiSuggestionVO getSuggestion(Long pictureId, User loginUser) {
        ThrowUtils.throwIf(pictureId == null || pictureId <= 0, ErrorCode.PARAMS_ERROR, "图片 id 不能为空");
        ThrowUtils.throwIf(loginUser == null, ErrorCode.NOT_LOGIN_ERROR);
        Picture picture = pictureService.getById(pictureId);
        ThrowUtils.throwIf(picture == null, ErrorCode.NOT_FOUND_ERROR, "图片不存在");
        boolean owner = Objects.equals(picture.getUserId(), loginUser.getId());
        boolean admin = Objects.equals(UserConstant.ADMIN_ROLE, loginUser.getUserRole());
        ThrowUtils.throwIf(!owner && !admin, ErrorCode.NO_AUTH_ERROR, "无权查看该图片的 AI 建议");
        PictureAiSuggestion suggestion = findByPictureId(pictureId);
        return suggestion == null ? null : toVO(suggestion);
    }

    /**
     * 调用模型并在可重试错误上按退避策略重试
     */
    private AiSuggestionPayload callWithRetry(String imageUrl, List<String> categoryDict,
                                              List<String> tagDict) {
        int attempt = 0;
        int maxRetries = Math.max(0, aiProperties.getMaxRetries());
        while (true) {
            try {
                return pictureAiTagger.suggest(imageUrl, categoryDict, tagDict);
            } catch (AiSuggestionException e) {
                if (!e.isRetryable() || attempt >= maxRetries) {
                    throw e;
                }
                attempt++;
                long delay = e.getRetryAfterMillis() > 0
                        ? Math.min(e.getRetryAfterMillis(), MAX_RETRY_AFTER_MILLIS)
                        : Math.max(0L, aiProperties.getRetryDelayMillis() * attempt);
                log.warn("AI 建议调用失败，{}ms 后第 {} 次重试: {}", delay, attempt, e.getMessage());
                sleep(delay);
            }
        }
    }

    /**
     * 校验并裁剪模型输出，确保不会写入超出数据库/表单限制的内容
     */
    AiSuggestionPayload sanitize(AiSuggestionPayload raw, List<String> categoryDict, Picture picture) {
        if (raw == null) {
            throw AiSuggestionException.permanent("模型未返回建议");
        }
        String name = StrUtil.trim(raw.name());
        if (StrUtil.isBlank(name)) {
            name = picture.getName();
        }
        if (StrUtil.isBlank(name)) {
            throw AiSuggestionException.permanent("模型未给出名称");
        }
        name = truncate(name, NAME_MAX_LENGTH);

        String introduction = truncate(StrUtil.trim(raw.introduction()), INTRODUCTION_MAX_LENGTH);

        String category = StrUtil.trim(raw.category());
        if (StrUtil.isNotBlank(category)) {
            if (categoryDict != null && !categoryDict.isEmpty() && !categoryDict.contains(category)) {
                log.info("AI 建议分类不在字典中，已忽略, category={}", category);
                category = null;
            } else {
                category = truncate(category, CATEGORY_MAX_LENGTH);
            }
        } else {
            category = null;
        }
        return new AiSuggestionPayload(name, StrUtil.emptyToNull(introduction), category,
                sanitizeTags(raw.tags()));
    }

    /**
     * 标签去重、去空、限制单个长度与总数量；非法标签直接丢弃，不阻断整条建议
     */
    List<String> sanitizeTags(List<String> rawTags) {
        if (rawTags == null || rawTags.isEmpty()) {
            return List.of();
        }
        Set<String> result = new LinkedHashSet<>();
        for (String rawTag : rawTags) {
            String tag = StrUtil.trim(rawTag);
            if (StrUtil.isBlank(tag)) {
                continue;
            }
            if (tag.length() > TAG_MAX_LENGTH) {
                log.info("AI 建议标签过长，已丢弃, tag={}", tag);
                continue;
            }
            result.add(tag);
            if (result.size() >= TAG_MAX_COUNT) {
                break;
            }
        }
        return new ArrayList<>(result);
    }

    /**
     * 构造缩略图地址，降低发送给模型的图片 token
     */
    static String buildThumbnailUrl(String url) {
        if (StrUtil.isBlank(url)) {
            return url;
        }
        if (url.contains("imageMogr2")) {
            return url;
        }
        String separator = url.contains("?") ? "&" : "?";
        return url + separator + "imageMogr2/thumbnail/1024x";
    }

    private boolean tryAcquireDailyQuota(Long userId) {
        if (userId == null || aiProperties.getDailyLimit() <= 0) {
            return false;
        }
        String key = DAILY_LIMIT_KEY_PREFIX + userId + ":"
                + LocalDate.now(AI_ZONE).format(DAY_FORMATTER);
        try {
            Long count = stringRedisTemplate.opsForValue().increment(key);
            if (count != null && count == 1L) {
                stringRedisTemplate.expire(key, DAILY_LIMIT_TTL);
            }
            return count != null && count <= aiProperties.getDailyLimit();
        } catch (RuntimeException e) {
            // Redis 异常时宁可放弃本次 AI 建议，也不放行无上限调用
            log.error("AI 建议每日计数失败，跳过本次生成, userId={}", userId, e);
            return false;
        }
    }

    /**
     * 图片重传（editTime 晚于建议创建时间）或处理中记录超时，视为旧建议
     */
    private boolean isStale(PictureAiSuggestion existing, Picture picture) {
        Date createTime = existing.getCreateTime();
        Date editTime = picture.getEditTime();
        if (createTime != null && editTime != null && createTime.before(editTime)) {
            return true;
        }
        return Objects.equals(existing.getStatus(), AiSuggestionStatusEnum.PROCESSING.getValue())
                && createTime != null
                && createTime.before(new Date(System.currentTimeMillis() - PROCESSING_TIMEOUT.toMillis()));
    }

    private PictureAiSuggestion findByPictureId(Long pictureId) {
        return pictureAiSuggestionMapper.selectOne(
                new QueryWrapper<PictureAiSuggestion>().eq("pictureId", pictureId));
    }

    private PictureAiSuggestion newRow(Long pictureId, AiSuggestionStatusEnum status) {
        PictureAiSuggestion suggestion = new PictureAiSuggestion();
        suggestion.setPictureId(pictureId);
        suggestion.setStatus(status.getValue());
        suggestion.setModel(aiProperties.getModel());
        suggestion.setCreateTime(new Date());
        return suggestion;
    }

    /**
     * 每日上限命中时写入失败记录，前端轮询可以立即停止
     */
    private void insertFailedRow(Long pictureId) {
        try {
            pictureAiSuggestionMapper.insert(newRow(pictureId, AiSuggestionStatusEnum.FAILED));
        } catch (DuplicateKeyException e) {
            log.debug("AI 失败记录已存在, pictureId={}", pictureId);
        }
    }

    private void markSuccess(Long id, AiSuggestionPayload suggestion) {
        PictureAiSuggestion update = new PictureAiSuggestion();
        update.setId(id);
        update.setName(suggestion.name());
        update.setIntroduction(suggestion.introduction());
        update.setCategory(suggestion.category());
        update.setTags(JSONUtil.toJsonStr(suggestion.tags() == null ? List.of() : suggestion.tags()));
        update.setModel(aiProperties.getModel());
        update.setStatus(AiSuggestionStatusEnum.SUCCESS.getValue());
        int result = pictureAiSuggestionMapper.updateById(update);
        if (result <= 0) {
            log.warn("AI 建议成功结果写入失败, id={}", id);
        }
    }

    private void markFailed(Long id) {
        PictureAiSuggestion update = new PictureAiSuggestion();
        update.setId(id);
        update.setStatus(AiSuggestionStatusEnum.FAILED.getValue());
        int result = pictureAiSuggestionMapper.updateById(update);
        if (result <= 0) {
            log.warn("AI 建议失败状态写入失败, id={}", id);
        }
    }

    private PictureAiSuggestionVO toVO(PictureAiSuggestion suggestion) {
        PictureAiSuggestionVO vo = new PictureAiSuggestionVO();
        vo.setPictureId(suggestion.getPictureId());
        vo.setName(suggestion.getName());
        vo.setIntroduction(suggestion.getIntroduction());
        vo.setCategory(suggestion.getCategory());
        vo.setTags(parseTags(suggestion.getTags()));
        vo.setModel(suggestion.getModel());
        vo.setStatus(suggestion.getStatus());
        vo.setCreateTime(suggestion.getCreateTime());
        return vo;
    }

    private List<String> parseTags(String tagsJson) {
        if (StrUtil.isBlank(tagsJson)) {
            return List.of();
        }
        try {
            List<String> tags = JSONUtil.toList(tagsJson, String.class);
            return tags == null ? List.of() : tags;
        } catch (RuntimeException e) {
            log.warn("解析 AI 建议标签失败, tags={}", tagsJson, e);
            return List.of();
        }
    }

    private void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw AiSuggestionException.permanent("AI 建议重试被中断", e);
        }
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}

package com.tzy.picturebackend.manager;

import cn.hutool.core.util.StrUtil;
import com.qcloud.cos.model.ciModel.auditing.AuditingJobsDetail;
import com.qcloud.cos.model.ciModel.auditing.AudtingCommonInfo;
import com.qcloud.cos.model.ciModel.auditing.TextAuditingResponse;
import com.tzy.picturebackend.config.TextReviewProperties;
import com.tzy.picturebackend.model.enums.TextReviewStatusEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * 数据万象文字审核封装：创建异步任务、轮询任务状态并把响应归一化成业务结果。
 * <p>
 * 文字审核接口本身是异步的（创建任务 + 查询任务），这里在调用线程内轮询到终态或超时；
 * 调用发生在 MQ 消费者里，不占用 HTTP 请求线程。只负责「拿到文审结论」，
 * 落库与自动处置由 {@code TextReviewService} 完成。
 */
@Component
@Slf4j
public class TextReviewManager {

    /**
     * textReviewLabel 的数据库长度
     */
    private static final int LABEL_MAX_LENGTH = 64;

    /**
     * 接口未返回标签时的占位值
     */
    private static final String UNKNOWN_LABEL = "Unknown";

    /**
     * 任务终态
     */
    private static final String STATE_SUCCESS = "Success";

    private static final String STATE_FAILED = "Failed";

    /**
     * 轮询间隔下限，避免配置成 0 时空转
     */
    private static final long MIN_POLL_INTERVAL_MS = 20;

    private final CosManager cosManager;

    private final TextReviewProperties textReviewProperties;

    public TextReviewManager(CosManager cosManager, TextReviewProperties textReviewProperties) {
        this.cosManager = cosManager;
        this.textReviewProperties = textReviewProperties;
    }

    /**
     * 审核一段文本。
     *
     * @param content 待审核文本（名称、简介拼装结果）
     * @return 归一化后的文审结果
     * @throws TextReviewException 任务创建失败、任务失败、等待超时或响应无法识别
     */
    public TextReviewResult review(String content) {
        if (StrUtil.isBlank(content)) {
            throw new TextReviewException("待审核文本为空");
        }
        AuditingJobsDetail created = createJob(content);
        // 少数情况下创建响应直接带终态，能省掉一次查询
        if (created != null && STATE_SUCCESS.equalsIgnoreCase(created.getState())) {
            return toResult(created);
        }
        String jobId = created == null ? null : created.getJobId();
        if (StrUtil.isBlank(jobId)) {
            throw new TextReviewException("数据万象文字审核未返回 JobId");
        }
        long deadline = System.currentTimeMillis() + Math.max(0, textReviewProperties.getPollTimeoutMs());
        while (true) {
            AuditingJobsDetail detail = describe(jobId);
            if (STATE_SUCCESS.equalsIgnoreCase(detail.getState())) {
                return toResult(detail);
            }
            if (STATE_FAILED.equalsIgnoreCase(detail.getState())) {
                throw new TextReviewException("数据万象文字审核失败, jobId=" + jobId
                        + ", code=" + detail.getCode()
                        + ", message=" + detail.getMessage());
            }
            if (System.currentTimeMillis() >= deadline) {
                throw new TextReviewException("数据万象文字审核超时, jobId=" + jobId
                        + ", state=" + detail.getState());
            }
            sleepQuietly(Math.max(textReviewProperties.getPollIntervalMs(), MIN_POLL_INTERVAL_MS));
        }
    }

    /**
     * 创建审核任务：content 需按接口要求做 Base64 编码
     */
    private AuditingJobsDetail createJob(String content) {
        String payload = Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8));
        TextAuditingResponse response;
        try {
            response = cosManager.createTextAuditingJob(payload, textReviewProperties.getBizType());
        } catch (RuntimeException e) {
            throw new TextReviewException("调用数据万象文字审核失败", e);
        }
        if (response == null) {
            throw new TextReviewException("数据万象文字审核创建任务返回空响应");
        }
        return response.getJobsDetail();
    }

    /**
     * 查询任务状态，响应异常统一转成 TextReviewException
     */
    private AuditingJobsDetail describe(String jobId) {
        TextAuditingResponse response;
        try {
            response = cosManager.describeTextAuditingJob(jobId);
        } catch (RuntimeException e) {
            throw new TextReviewException("查询数据万象文字审核任务失败, jobId=" + jobId, e);
        }
        if (response == null || response.getJobsDetail() == null) {
            throw new TextReviewException("数据万象文字审核查询返回空响应, jobId=" + jobId);
        }
        return response.getJobsDetail();
    }

    /**
     * 把任务详情归一化：状态、标签、置信度
     */
    static TextReviewResult toResult(AuditingJobsDetail detail) {
        TextReviewStatusEnum status = TextReviewStatusEnum.fromResult(detail.getResult());
        if (status == null) {
            throw new TextReviewException("无法识别的文审结果, result=" + detail.getResult()
                    + ", code=" + detail.getCode()
                    + ", message=" + detail.getMessage());
        }
        return new TextReviewResult(status, buildLabel(detail.getLabel(), detail.getSubLabel()),
                maxScore(detail));
    }

    /**
     * 主标签与子标签拼接后截断到数据库长度；都为空时使用 Unknown
     */
    static String buildLabel(String label, String subLabel) {
        String mainLabel = StrUtil.blankToDefault(label, UNKNOWN_LABEL).trim();
        String fullLabel = StrUtil.isBlank(subLabel) ? mainLabel : mainLabel + "/" + subLabel.trim();
        return fullLabel.length() > LABEL_MAX_LENGTH
                ? fullLabel.substring(0, LABEL_MAX_LENGTH)
                : fullLabel;
    }

    /**
     * 各审核场景都会返回自己的置信度，取其中的最大值作为整体置信度
     */
    static Integer maxScore(AuditingJobsDetail detail) {
        List<AudtingCommonInfo> infos = new ArrayList<>();
        infos.add(detail.getPornInfo());
        infos.add(detail.getTerroristInfo());
        infos.add(detail.getPoliticsInfo());
        infos.add(detail.getAdsInfo());
        infos.add(detail.getAbuseInfo());
        infos.add(detail.getIllegalInfo());
        infos.add(detail.getTeenagerInfo());
        infos.add(detail.getMeaninglessInfo());
        Integer max = null;
        for (AudtingCommonInfo info : infos) {
            if (info == null) {
                continue;
            }
            Integer score = parseScore(info.getScore());
            if (score != null && (max == null || score > max)) {
                max = score;
            }
        }
        return max;
    }

    /**
     * 解析 0-100 的置信度；非法值返回 null，避免把脏数据写进数据库
     */
    static Integer parseScore(String score) {
        if (StrUtil.isBlank(score)) {
            return null;
        }
        try {
            int value = Integer.parseInt(score.trim());
            if (value < 0 || value > 100) {
                log.warn("文审置信度超出 0-100，忽略, score = {}", score);
                return null;
            }
            return value;
        } catch (NumberFormatException e) {
            log.warn("文审置信度不是整数，忽略, score = {}", score);
            return null;
        }
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TextReviewException("等待文字审核结果时被中断", e);
        }
    }
}

package com.tzy.picturebackend.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.tzy.picturebackend.config.TextReviewProperties;
import com.tzy.picturebackend.event.NotificationEventPublisher;
import com.tzy.picturebackend.event.PictureEventPublisher;
import com.tzy.picturebackend.manager.PictureListCache;
import com.tzy.picturebackend.manager.TextReviewManager;
import com.tzy.picturebackend.manager.TextReviewResult;
import com.tzy.picturebackend.model.dto.notification.NotificationMessage;
import com.tzy.picturebackend.model.entity.Picture;
import com.tzy.picturebackend.model.enums.NotificationTypeEnum;
import com.tzy.picturebackend.model.enums.PictureReviewStatusEnum;
import com.tzy.picturebackend.model.enums.TextReviewModeEnum;
import com.tzy.picturebackend.model.enums.TextReviewStatusEnum;
import com.tzy.picturebackend.service.PictureReviewLogService;
import com.tzy.picturebackend.service.PictureService;
import com.tzy.picturebackend.service.TextReviewService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Date;

/**
 * 文本审核实现。
 * <p>
 * 写库分成两步，全部使用带条件的更新：
 * <ol>
 *   <li>写文审字段，条件 {@code textReviewStatus IS NULL}，保证重复投递只处理一次；</li>
 *   <li>自动处置时改审核状态，条件 {@code reviewStatus = 待审核}，保证不会覆盖人工结论。</li>
 * </ol>
 */
@Service
@Slf4j
public class TextReviewServiceImpl implements TextReviewService {

    /**
     * reviewMessage 字段长度
     */
    private static final int REVIEW_MESSAGE_MAX_LENGTH = 512;

    private final TextReviewManager textReviewManager;

    private final TextReviewProperties textReviewProperties;

    private final PictureService pictureService;

    private final PictureReviewLogService pictureReviewLogService;

    private final PictureListCache pictureListCache;

    private final PictureEventPublisher pictureEventPublisher;

    private final NotificationEventPublisher notificationEventPublisher;

    public TextReviewServiceImpl(TextReviewManager textReviewManager,
                                 TextReviewProperties textReviewProperties,
                                 PictureService pictureService,
                                 PictureReviewLogService pictureReviewLogService,
                                 PictureListCache pictureListCache,
                                 PictureEventPublisher pictureEventPublisher,
                                 NotificationEventPublisher notificationEventPublisher) {
        this.textReviewManager = textReviewManager;
        this.textReviewProperties = textReviewProperties;
        this.pictureService = pictureService;
        this.pictureReviewLogService = pictureReviewLogService;
        this.pictureListCache = pictureListCache;
        this.pictureEventPublisher = pictureEventPublisher;
        this.notificationEventPublisher = notificationEventPublisher;
    }

    @Override
    public void review(Picture picture) {
        if (!textReviewProperties.isEnabled() || picture == null || picture.getId() == null) {
            return;
        }
        if (picture.getTextReviewStatus() != null) {
            log.info("图片已完成文审，跳过, pictureId={}, textReviewStatus={}",
                    picture.getId(), picture.getTextReviewStatus());
            return;
        }
        String content = buildContent(picture, textReviewProperties.getMaxInputLength());
        if (StrUtil.isBlank(content)) {
            // 名称与简介都为空没有任何可审内容，直接跳过，避免无意义的接口调用与费用
            log.debug("图片没有可审核文本，跳过文审, pictureId={}", picture.getId());
            return;
        }
        TextReviewResult result = textReviewManager.review(content);
        Date reviewTime = new Date();
        boolean saved = pictureService.update(new UpdateWrapper<Picture>()
                .eq("id", picture.getId())
                .isNull("textReviewStatus")
                .set("textReviewStatus", result.status().getValue())
                .set("textReviewLabel", result.label())
                .set("textReviewScore", result.score())
                .set("textReviewTime", reviewTime));
        if (!saved) {
            // 重复投递：文审字段已经被写过，不再重复处置
            log.info("文审字段已存在，跳过重复处理, pictureId={}", picture.getId());
            return;
        }
        applyMode(picture, result, reviewTime);
    }

    /**
     * 名称 + 简介拼成审核文本，空值跳过，超长按配置截断
     */
    static String buildContent(Picture picture, int maxLength) {
        StringBuilder builder = new StringBuilder();
        appendPart(builder, picture.getName());
        appendPart(builder, picture.getIntroduction());
        String content = builder.toString();
        int limit = Math.max(maxLength, 1);
        return content.length() > limit ? content.substring(0, limit) : content;
    }

    private static void appendPart(StringBuilder builder, String part) {
        String trimmed = StrUtil.trim(part);
        if (StrUtil.isEmpty(trimmed)) {
            return;
        }
        if (!builder.isEmpty()) {
            builder.append('\n');
        }
        builder.append(trimmed);
    }

    /**
     * 按模式决定文审结果的效力：assist 只记录；auto-reject 违规自动拒绝，疑似与正常交给人工。
     */
    private void applyMode(Picture picture, TextReviewResult result, Date reviewTime) {
        TextReviewModeEnum mode = textReviewProperties.getMode();
        if (mode != TextReviewModeEnum.AUTO_REJECT || result.status() != TextReviewStatusEnum.VIOLATION) {
            return;
        }
        String reviewMessage = buildAutoReviewMessage(result);
        // 条件更新：只有图片仍处于待审核时才自动处置，避免覆盖人工审核结果
        boolean updated = pictureService.update(new UpdateWrapper<Picture>()
                .eq("id", picture.getId())
                .eq("reviewStatus", PictureReviewStatusEnum.REVIEWING.getValue())
                .set("reviewStatus", PictureReviewStatusEnum.REJECT.getValue())
                .set("reviewMessage", reviewMessage)
                .set("reviewerId", PictureReviewLogService.SYSTEM_REVIEWER_ID)
                .set("reviewTime", reviewTime));
        if (!updated) {
            log.info("人工审核已先完成，保留人工结论, pictureId={}, textStatus={}",
                    picture.getId(), result.status().getText());
            return;
        }
        pictureReviewLogService.record(picture.getId(), PictureReviewLogService.SYSTEM_REVIEWER_ID,
                PictureReviewStatusEnum.REJECT.getValue(), reviewMessage);
        // 与人工审核共用 dedupeKey，重复处置不会产生第二条通知
        if (picture.getUserId() != null) {
            notificationEventPublisher.publish(NotificationMessage.review(
                    NotificationTypeEnum.REVIEW_REJECT, picture.getUserId(),
                    picture.getId(), picture.getName(), reviewMessage));
        }
        // 审核状态变化会影响公开列表与搜索索引
        pictureListCache.invalidate();
        pictureEventPublisher.pictureChanged(picture.getId());
    }

    /**
     * 自动处置时写入审核意见，长度不超过数据库字段限制
     */
    static String buildAutoReviewMessage(TextReviewResult result) {
        String label = StrUtil.blankToDefault(result.label(), "未知标签");
        String score = result.score() == null ? "" : "，置信度 " + result.score();
        String message = "文审违规（" + label + score + "），自动拒绝";
        return message.length() > REVIEW_MESSAGE_MAX_LENGTH
                ? message.substring(0, REVIEW_MESSAGE_MAX_LENGTH)
                : message;
    }
}

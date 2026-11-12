package com.tzy.picturebackend.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.tzy.picturebackend.config.MachineReviewProperties;
import com.tzy.picturebackend.event.NotificationEventPublisher;
import com.tzy.picturebackend.event.PictureEventPublisher;
import com.tzy.picturebackend.manager.MachineReviewManager;
import com.tzy.picturebackend.manager.MachineReviewResult;
import com.tzy.picturebackend.manager.PictureListCache;
import com.tzy.picturebackend.model.dto.notification.NotificationMessage;
import com.tzy.picturebackend.model.entity.Picture;
import com.tzy.picturebackend.model.enums.MachineReviewModeEnum;
import com.tzy.picturebackend.model.enums.MachineReviewStatusEnum;
import com.tzy.picturebackend.model.enums.NotificationTypeEnum;
import com.tzy.picturebackend.model.enums.PictureReviewStatusEnum;
import com.tzy.picturebackend.service.MachineReviewService;
import com.tzy.picturebackend.service.PictureReviewLogService;
import com.tzy.picturebackend.service.PictureService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.Objects;

/**
 * 机审初筛实现。
 * <p>
 * 写库分成两步，全部使用带条件的更新：
 * <ol>
 *   <li>写机审字段，条件 {@code machineReviewStatus IS NULL}，保证重复投递只处理一次；</li>
 *   <li>自动处置时改审核状态，条件 {@code reviewStatus = 待审核}，保证不会覆盖人工结论。</li>
 * </ol>
 */
@Service
@Slf4j
public class MachineReviewServiceImpl implements MachineReviewService {

    /**
     * reviewMessage 字段长度
     */
    private static final int REVIEW_MESSAGE_MAX_LENGTH = 512;

    private final MachineReviewManager machineReviewManager;

    private final MachineReviewProperties machineReviewProperties;

    private final PictureService pictureService;

    private final PictureReviewLogService pictureReviewLogService;

    private final PictureListCache pictureListCache;

    private final PictureEventPublisher pictureEventPublisher;

    private final NotificationEventPublisher notificationEventPublisher;

    public MachineReviewServiceImpl(MachineReviewManager machineReviewManager,
                                    MachineReviewProperties machineReviewProperties,
                                    PictureService pictureService,
                                    PictureReviewLogService pictureReviewLogService,
                                    PictureListCache pictureListCache,
                                    PictureEventPublisher pictureEventPublisher,
                                    NotificationEventPublisher notificationEventPublisher) {
        this.machineReviewManager = machineReviewManager;
        this.machineReviewProperties = machineReviewProperties;
        this.pictureService = pictureService;
        this.pictureReviewLogService = pictureReviewLogService;
        this.pictureListCache = pictureListCache;
        this.pictureEventPublisher = pictureEventPublisher;
        this.notificationEventPublisher = notificationEventPublisher;
    }

    @Override
    public void review(Picture picture) {
        if (!machineReviewProperties.isEnabled() || picture == null || picture.getId() == null) {
            return;
        }
        if (!Objects.equals(picture.getReviewStatus(), PictureReviewStatusEnum.REVIEWING.getValue())) {
            log.info("图片已不是待审核状态，跳过机审, pictureId={}, reviewStatus={}",
                    picture.getId(), picture.getReviewStatus());
            return;
        }
        MachineReviewResult result = machineReviewManager.review(picture.getUrl());
        Date reviewTime = new Date();
        boolean saved = pictureService.update(new UpdateWrapper<Picture>()
                .eq("id", picture.getId())
                .isNull("machineReviewStatus")
                .set("machineReviewStatus", result.status().getValue())
                .set("machineReviewLabel", result.label())
                .set("machineReviewScore", result.score())
                .set("machineReviewTime", reviewTime));
        if (!saved) {
            // 重复投递或兜底任务并发处理：机审字段已经被写过，不再重复处置
            log.info("机审字段已存在，跳过重复处理, pictureId={}", picture.getId());
            return;
        }
        // 机审完成：通知 AI 标签队列；是否改变审核状态由 mode 决定
        pictureEventPublisher.pictureModerated(picture.getId());
        applyMode(picture, result, reviewTime);
    }

    /**
     * 按模式决定机审结果的效力。
     * <p>
     * assist 只记录；auto-reject 只拒绝违规；auto 拒绝违规、通过正常；疑似始终交给人工。
     */
    private void applyMode(Picture picture, MachineReviewResult result, Date reviewTime) {
        MachineReviewModeEnum mode = machineReviewProperties.getMode();
        if (mode == null || mode == MachineReviewModeEnum.ASSIST
                || result.status() == MachineReviewStatusEnum.SUSPECT) {
            return;
        }
        Integer targetStatus = switch (result.status()) {
            case VIOLATION -> PictureReviewStatusEnum.REJECT.getValue();
            case NORMAL -> mode == MachineReviewModeEnum.AUTO
                    ? PictureReviewStatusEnum.PASS.getValue()
                    : null;
            default -> null;
        };
        if (targetStatus == null) {
            return;
        }
        String reviewMessage = buildAutoReviewMessage(result, targetStatus);
        // 条件更新：只有图片仍处于待审核时才自动处置，避免覆盖人工审核结果
        boolean updated = pictureService.update(new UpdateWrapper<Picture>()
                .eq("id", picture.getId())
                .eq("reviewStatus", PictureReviewStatusEnum.REVIEWING.getValue())
                .set("reviewStatus", targetStatus)
                .set("reviewMessage", reviewMessage)
                .set("reviewerId", PictureReviewLogService.SYSTEM_REVIEWER_ID)
                .set("reviewTime", reviewTime));
        if (!updated) {
            log.info("人工审核已先完成，保留人工结论, pictureId={}, machineStatus={}",
                    picture.getId(), result.status().getText());
            return;
        }
        pictureReviewLogService.record(picture.getId(), PictureReviewLogService.SYSTEM_REVIEWER_ID,
                targetStatus, reviewMessage);
        // 机审自动处置同样要告知作者：dedupeKey 与人工审核一致，重复审核不会产生第二条
        if (picture.getUserId() != null) {
            notificationEventPublisher.publish(NotificationMessage.review(
                    targetStatus == PictureReviewStatusEnum.PASS.getValue()
                            ? NotificationTypeEnum.REVIEW_PASS
                            : NotificationTypeEnum.REVIEW_REJECT,
                    picture.getUserId(), picture.getId(), picture.getName(), reviewMessage));
        }
        // 审核状态变化会影响公开列表与搜索索引
        pictureListCache.invalidate();
        pictureEventPublisher.pictureChanged(picture.getId());
    }

    /**
     * 自动处置时写入审核意见，长度不超过数据库字段限制
     */
    static String buildAutoReviewMessage(MachineReviewResult result, int targetStatus) {
        String message;
        if (targetStatus == PictureReviewStatusEnum.PASS.getValue()) {
            message = "机审正常，自动通过";
        } else {
            String label = StrUtil.blankToDefault(result.label(), "未知标签");
            String score = result.score() == null ? "" : "，置信度 " + result.score();
            message = "机审违规（" + label + score + "），自动拒绝";
        }
        return message.length() > REVIEW_MESSAGE_MAX_LENGTH
                ? message.substring(0, REVIEW_MESSAGE_MAX_LENGTH)
                : message;
    }
}

package com.tzy.picturebackend.manager;

import cn.hutool.core.util.StrUtil;
import com.qcloud.cos.model.ciModel.auditing.ImageAuditingResponse;
import com.tzy.picturebackend.model.enums.MachineReviewStatusEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 数据万象图片审核封装：解析对象键、调用审核接口并把响应归一化成业务结果。
 * <p>
 * 只负责「拿到机审结论」，不读写数据库、不改变审核状态；
 * 落库和自动处置由 {@code MachineReviewService} 完成。
 */
@Component
@Slf4j
public class MachineReviewManager {

    /**
     * machineReviewLabel 的数据库长度
     */
    private static final int LABEL_MAX_LENGTH = 64;

    /**
     * 接口未返回标签时的占位值
     */
    private static final String UNKNOWN_LABEL = "Unknown";

    private final CosManager cosManager;

    public MachineReviewManager(CosManager cosManager) {
        this.cosManager = cosManager;
    }

    /**
     * 审核一张图片。
     *
     * @param imageUrl 图片访问地址（必须是当前 COS 桶的对象）
     * @return 归一化后的机审结果
     * @throws MachineReviewException 对象键无法解析、接口调用失败或响应无法识别
     */
    public MachineReviewResult review(String imageUrl) {
        String objectKey = cosManager.parseObjectKey(imageUrl);
        if (StrUtil.isBlank(objectKey)) {
            throw new MachineReviewException("无法从图片地址解析 COS 对象键: " + imageUrl);
        }
        ImageAuditingResponse response;
        try {
            response = cosManager.auditImage(objectKey);
        } catch (RuntimeException e) {
            throw new MachineReviewException("调用数据万象图片审核失败, objectKey=" + objectKey, e);
        }
        if (response == null) {
            throw new MachineReviewException("数据万象图片审核返回空响应, objectKey=" + objectKey);
        }
        MachineReviewStatusEnum status = MachineReviewStatusEnum.fromResult(response.getResult());
        if (status == null) {
            throw new MachineReviewException("无法识别的机审结果, objectKey=" + objectKey
                    + ", result=" + response.getResult()
                    + ", code=" + response.getCode()
                    + ", message=" + response.getMessage());
        }
        return new MachineReviewResult(status, buildLabel(response.getLabel(), response.getSubLabel()),
                parseScore(response.getScore()));
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
     * 解析 0-100 的置信度；非法值返回 null，避免把脏数据写进数据库
     */
    static Integer parseScore(String score) {
        if (StrUtil.isBlank(score)) {
            return null;
        }
        try {
            int value = Integer.parseInt(score.trim());
            if (value < 0 || value > 100) {
                log.warn("机审置信度超出 0-100，忽略, score = {}", score);
                return null;
            }
            return value;
        } catch (NumberFormatException e) {
            log.warn("机审置信度不是整数，忽略, score = {}", score);
            return null;
        }
    }
}
